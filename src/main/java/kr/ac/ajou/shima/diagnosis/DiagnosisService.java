package kr.ac.ajou.shima.diagnosis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ac.ajou.shima.diagnosis.NaverSearchClient.BlogItem;
import kr.ac.ajou.shima.diagnosis.NaverSearchClient.BlogResponse;
import kr.ac.ajou.shima.diagnosis.NaverSearchClient.LocalItem;
import kr.ac.ajou.shima.domain.DiagnosisResult;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.VisitLog;
import kr.ac.ajou.shima.dto.DiagnosisRequest;
import kr.ac.ajou.shima.dto.DiagnosisResponse;
import kr.ac.ajou.shima.dto.DiagnosisResponse.Axis;
import kr.ac.ajou.shima.repository.DiagnosisResultRepository;
import kr.ac.ajou.shima.repository.VisitLogRepository;
import kr.ac.ajou.shima.service.StatsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 쉬마 브랜드 지수 (F1 수집 + F2 점수). 점수는 전부 결정론적 규칙 — LLM 사용 안 함(재현성·설명 가능성).
 * 측정 못 한 축은 0점이 아니라 "제외"하고, 측정된 축 만점 기준으로 100점 환산한다.
 *
 * 네이버 호출: 지역검색 2회 + 블로그검색 2회 = 진단 1회당 4회
 */
@Service
public class DiagnosisService {

    private static final Pattern DONG = Pattern.compile("([가-힣0-9]+(?:동|읍|면|가))(?=\\s|$)");
    private static final DateTimeFormatter POSTDATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private final NaverSearchClient naver;
    private final VisitLogRepository visitRepo;
    private final StatsService statsService;
    private final DiagnosisResultRepository resultRepo;
    private final ObjectMapper objectMapper;

    public DiagnosisService(NaverSearchClient naver, VisitLogRepository visitRepo, StatsService statsService,
                            DiagnosisResultRepository resultRepo, ObjectMapper objectMapper) {
        this.naver = naver;
        this.visitRepo = visitRepo;
        this.statsService = statsService;
        this.resultRepo = resultRepo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DiagnosisResponse run(Store store, DiagnosisRequest req) {
        String name = blankTo(req == null ? null : req.storeName(), store.getName());
        String region = blankTo(req == null ? null : req.region(), store.getRegion() == null ? "" : store.getRegion());
        Integer reviewCount = req == null ? null : req.visitorReviewCount();

        // ---- 1) 가게 식별: "지역 상호명" 지역검색 → 상호명이 들어간 결과 ----
        LocalItem place = naver.local((region + " " + name).trim()).stream()
                .filter(i -> sameName(i.title(), name)).findFirst().orElse(null);

        String dong = place == null ? region : extractDong(place);
        String categoryKw = place == null ? null : categoryKeyword(place.category());
        String keyword = categoryKw == null ? null : (dong + " " + categoryKw).trim();

        // ---- 2) 블로그: 정확도순 100건(언급량 보정) + 최신순 100건(최근 활동) ----
        String blogQuery = (name + " " + (dong.isBlank() ? region : dong)).trim();
        BlogResponse blogSim = naver.blog(blogQuery, "sim");
        BlogResponse blogDate = naver.blog(blogQuery, "date");

        List<Axis> axes = new ArrayList<>();
        axes.add(exposure(place, name, keyword, blogSim));
        axes.add(reputation(reviewCount));
        axes.add(activity(blogDate, name));
        axes.add(completeness(place));
        axes.add(revisit(store));

        int got = axes.stream().filter(Axis::measured).mapToInt(Axis::points).sum();
        int max = axes.stream().filter(Axis::measured).mapToInt(Axis::max).sum();
        int total = max == 0 ? 0 : (int) Math.round(got * 100.0 / max);

        Integer previous = resultRepo.findFirstByStoreOrderByCreatedAtDesc(store)
                .map(DiagnosisResult::getTotalScore).orElse(null);

        DiagnosisResponse res = new DiagnosisResponse(Instant.now(), total, max,
                place == null ? null : NaverSearchClient.clean(place.title()),
                place == null ? null : blankTo(place.roadAddress(), place.address()),
                keyword, axes, previous);

        resultRepo.save(new DiagnosisResult(store, total, toJson(res)));
        return res;
    }

    @Transactional(readOnly = true)
    public Optional<DiagnosisResponse> latest(Store store) {
        return resultRepo.findFirstByStoreOrderByCreatedAtDesc(store).map(r -> fromJson(r.getDetailJson()));
    }

    // ======================= 축별 규칙 =======================

    /** 찾기 쉬움 (20) = 동네+업종 지역검색 순위(10) + 블로그 언급량(10) */
    private Axis exposure(LocalItem place, String name, String keyword, BlogResponse blogSim) {
        List<String> ev = new ArrayList<>();
        int mapPts = 0;
        if (place == null) {
            ev.add("네이버 지도 검색에서 가게를 찾지 못했어요 (상호명·지역을 확인해 주세요)");
        } else if (keyword != null) {
            List<LocalItem> top = naver.local(keyword);
            int rank = -1;
            for (int i = 0; i < top.size(); i++) {
                if (sameName(top.get(i).title(), name)) { rank = i + 1; break; }
            }
            if (rank > 0) {
                mapPts = 11 - rank; // 1위 10점 … 5위 6점
                ev.add("'" + keyword + "' 검색 시 " + rank + "번째로 나와요");
            } else {
                ev.add("'" + keyword + "' 검색 상위 5곳에 없어요");
            }
        }

        long mentions = estimatedMentions(blogSim, name);
        int blogPts = mentions == 0 ? 0 : mentions < 10 ? 3 : mentions < 50 ? 6 : mentions < 200 ? 8 : 10;
        ev.add("블로그 언급 약 " + mentions + "건");

        return new Axis("exposure", "찾기 쉬움", "손님이 찾기 쉬운 정도", "노출", mapPts + blogPts, 20, true, ev);
    }

    /** 믿음직함 (20) — 리뷰 공식 API 없음 → 사장님 입력 리뷰 수. 미입력이면 측정 제외 */
    private Axis reputation(Integer reviews) {
        if (reviews == null) {
            return new Axis("reputation", "믿음직함", "손님이 믿을 만한 정도", "평판", 0, 20, false,
                    List.of("리뷰 수는 네이버가 공개 API로 주지 않아요 — 네이버 지도의 '방문자 리뷰' 숫자를 입력하면 계산돼요"));
        }
        int pts = reviews == 0 ? 0 : reviews < 10 ? 5 : reviews < 50 ? 10 : reviews < 200 ? 15 : 20;
        return new Axis("reputation", "믿음직함", "손님이 믿을 만한 정도", "평판", pts, 20, true,
                List.of("방문자 리뷰 " + reviews + "개 (사장님 입력)"));
    }

    /** 살아있음 (15) — 최근 블로그 새 글 (상호명이 실제로 들어간 글만) */
    private Axis activity(BlogResponse blogDate, String name) {
        LocalDate today = LocalDate.now(StatsService.KST);
        int d30 = 0, d90 = 0;
        for (BlogItem b : blogDate.items()) {
            if (!mentions(b, name)) continue;
            LocalDate d = parsePostdate(b.postdate());
            if (d == null) continue;
            if (!d.isBefore(today.minusDays(30))) d30++;
            if (!d.isBefore(today.minusDays(90))) d90++;
        }
        int pts = d30 >= 5 ? 15 : d30 >= 2 ? 10 : d30 == 1 ? 6 : d90 >= 1 ? 3 : 0;
        return new Axis("activity", "살아있음", "가게 소식이 살아있는 정도", "활성도", pts, 15, true,
                List.of("최근 한 달 블로그 새 글 " + d30 + "개", "최근 3달 " + d90 + "개"));
    }

    /** 빠짐없음 (15) — 검색 API로 확인 가능한 항목만: 주소·업종 분류·홈페이지 (각 5점) */
    private Axis completeness(LocalItem place) {
        if (place == null) {
            return new Axis("completeness", "빠짐없음", "가게 정보가 빠짐없는 정도", "정보 완결성", 0, 15, false,
                    List.of("가게를 찾지 못해 확인하지 못했어요"));
        }
        boolean addr = !blankTo(place.roadAddress(), place.address()).isBlank();
        boolean cat = place.category() != null && !place.category().isBlank();
        boolean home = place.link() != null && !place.link().isBlank();
        int pts = (addr ? 5 : 0) + (cat ? 5 : 0) + (home ? 5 : 0);
        List<String> ev = new ArrayList<>();
        ev.add("주소 " + (addr ? "있음" : "없음") + " · 업종 분류 " + (cat ? "있음" : "없음") + " · 홈페이지/SNS 링크 " + (home ? "있음" : "없음"));
        ev.add("영업시간·메뉴·사진·전화번호는 검색 API로 확인할 수 없어 제외했어요");
        return new Axis("completeness", "빠짐없음", "가게 정보가 빠짐없는 정도", "정보 완결성", pts, 15, true, ev);
    }

    /** 다시 옴 (30) = 최근 4주 방문 손님 중 누적 2회 이상 비율(20) + 평균 방문 주기(10). 손님 5명 미만이면 제외 */
    private Axis revisit(Store store) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(28));
        Map<Long, Integer> visitsByCustomer = new HashMap<>();
        Set<Long> inWindow = new HashSet<>();
        for (VisitLog v : visitRepo.findByStoreAndVisitedAtBefore(store, now.plusSeconds(1))) {
            Long id = v.getCustomer().getId();
            visitsByCustomer.merge(id, 1, Integer::sum);
            if (!v.getVisitedAt().isBefore(windowStart)) inWindow.add(id);
        }
        int visitors = inWindow.size();
        if (visitors < 5) {
            return new Axis("revisit", "다시 옴", "다시 오는 손님", "재방문", 0, 30, false,
                    List.of("최근 4주 적립 손님이 " + visitors + "명이라 아직 판단하기 일러요 (5명부터 계산)"));
        }
        long returning = inWindow.stream().filter(id -> visitsByCustomer.get(id) >= 2).count();
        double rate = (double) returning / visitors;
        int ratePts = (int) Math.min(20, Math.round(rate * 40)); // 50% 이상이면 만점
        Double cycle = statsService.avgCycleDays(store, now.plusSeconds(1));
        int cyclePts = cycle == null ? 0 : cycle <= 14 ? 10 : cycle <= 21 ? 7 : cycle <= 30 ? 4 : 1;

        List<String> ev = new ArrayList<>();
        ev.add("최근 4주 손님 " + visitors + "명 중 두 번 이상 오신 분 " + returning + "명 (" + Math.round(rate * 100) + "%)");
        ev.add(cycle == null ? "두 번 이상 오신 분이 아직 없어요" : "단골 평균 방문 간격 " + Math.round(cycle) + "일");
        return new Axis("revisit", "다시 옴", "다시 오는 손님", "재방문", ratePts + cyclePts, 30, true, ev);
    }

    // ======================= 도우미 =======================

    /**
     * 검색 결과 이름과 상호명이 같은 가게인지 (공백·태그 무시).
     *  - 결과가 상호명을 포함: "아로새길 수원점" ⊃ "아로새길"
     *  - 입력 상호가 결과 이름으로 시작: "아로새길카페" → "아로새길"
     *  (단순 부분일치는 "로새길" 같은 오탐이 생겨서 쓰지 않음)
     */
    static boolean sameName(String title, String name) {
        String a = NaverSearchClient.norm(title), b = NaverSearchClient.norm(name);
        return !a.isEmpty() && !b.isEmpty() && (a.contains(b) || (a.length() >= 2 && b.startsWith(a)));
    }

    private static boolean mentions(BlogItem b, String name) {
        String n = NaverSearchClient.norm(name);
        return NaverSearchClient.norm(b.title()).contains(n) || NaverSearchClient.norm(b.description()).contains(n);
    }

    /** 블로그 전체 건수 × (정확도순 상위 결과 중 상호명이 실제로 들어간 비율) — 동명이·무관 글 보정 */
    private static long estimatedMentions(BlogResponse r, String name) {
        if (r.items().isEmpty()) return 0;
        long relevant = r.items().stream().filter(b -> mentions(b, name)).count();
        return Math.round(r.total() * ((double) relevant / r.items().size()));
    }

    /** 지번 주소에서 동/읍/면/가 추출 → 없으면 도로명 주소의 구/시 */
    static String extractDong(LocalItem p) {
        Matcher m = DONG.matcher(blankTo(p.address(), ""));
        if (m.find()) return m.group(1);
        for (String tok : blankTo(p.roadAddress(), "").split("\\s+")) {
            if (tok.endsWith("구")) return tok;
        }
        for (String tok : blankTo(p.roadAddress(), "").split("\\s+")) {
            if (tok.endsWith("시")) return tok;
        }
        return "";
    }

    /** "카페,디저트>카페" → "카페", "한식>육류,고기요리" → "육류" */
    static String categoryKeyword(String category) {
        if (category == null || category.isBlank()) return null;
        String[] parts = category.split(">");
        return parts[parts.length - 1].split(",")[0].trim();
    }

    private static LocalDate parsePostdate(String s) {
        try { return s == null ? null : LocalDate.parse(s, POSTDATE); } catch (Exception e) { return null; }
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    private String toJson(DiagnosisResponse r) {
        try { return objectMapper.writeValueAsString(r); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private DiagnosisResponse fromJson(String s) {
        try { return objectMapper.readValue(s, DiagnosisResponse.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
