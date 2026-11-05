package kr.ac.ajou.shima.diagnosis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.ac.ajou.shima.diagnosis.KakaoLocalClient.KakaoPlace;
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
 * 외부 호출: 네이버 지역 2 + 블로그 2, 카카오 키워드 2 = 진단 1회당 6회 (카카오는 실패해도 해당 항목만 제외)
 *
 * 축(만점): 찾기 쉬움 20(네이버 지도 8 + 카카오맵 4 + 블로그 8) · AI가 찾기 쉬움 10 · 믿음직함 20 · 살아있음 15
 *           · 빠짐없음 15 · 다시 옴 30
 */
@Service
public class DiagnosisService {

    private static final Pattern DONG = Pattern.compile("([가-힣0-9]+(?:동|읍|면|가))(?=\\s|$)");
    private static final DateTimeFormatter POSTDATE = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    private final NaverSearchClient naver;
    private final KakaoLocalClient kakao;
    private final VisitLogRepository visitRepo;
    private final StatsService statsService;
    private final DiagnosisResultRepository resultRepo;
    private final ObjectMapper objectMapper;

    public DiagnosisService(NaverSearchClient naver, KakaoLocalClient kakao, VisitLogRepository visitRepo, StatsService statsService,
                            DiagnosisResultRepository resultRepo, ObjectMapper objectMapper) {
        this.naver = naver;
        this.kakao = kakao;
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

        KakaoLookup kk = kakaoLookup(region, name, keyword);

        List<Axis> axes = new ArrayList<>();
        axes.add(exposure(place, name, keyword, blogSim, kk));
        axes.add(aiReadiness(place, kk, reviewCount, blogSim, name));
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

    // ---------- 카카오맵 조회 (실패해도 진단은 계속) ----------

    /** measured=false면 카카오 항목 제외. rank: 키워드 검색 순위(1~15), 없으면 -1, 검색 안 함 0 */
    record KakaoLookup(boolean measured, KakaoPlace place, int rank, String note) {}

    private KakaoLookup kakaoLookup(String region, String name, String keyword) {
        if (!kakao.isConfigured()) return new KakaoLookup(false, null, 0, "카카오맵은 확인하지 않았어요 (키 미설정)");
        try {
            KakaoPlace place = kakao.keyword((region + " " + name).trim(), 5).stream()
                    .filter(p -> sameName(p.placeName(), name)).findFirst().orElse(null);
            int rank = 0;
            if (keyword != null) {
                List<KakaoPlace> top = kakao.keyword(keyword, 15);
                rank = -1;
                for (int i = 0; i < top.size(); i++) {
                    if (sameName(top.get(i).placeName(), name)) { rank = i + 1; break; }
                }
            }
            return new KakaoLookup(true, place, rank, null);
        } catch (ExternalApiException e) {
            return new KakaoLookup(false, null, 0, "카카오맵을 확인하지 못했어요 (" + e.getMessage() + ")");
        }
    }

    // ======================= 축별 규칙 =======================

    /** 찾기 쉬움 (20) = 네이버 지도 순위(8) + 카카오맵 순위(4, 확인 못 하면 제외) + 블로그 언급량(8) */
    private Axis exposure(LocalItem place, String name, String keyword, BlogResponse blogSim, KakaoLookup kk) {
        List<String> ev = new ArrayList<>();
        int max = 16;
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
                mapPts = 9 - rank; // 1위 8점 … 5위 4점
                ev.add("네이버 지도 '" + keyword + "' 검색 시 " + rank + "번째로 나와요");
            } else {
                ev.add("네이버 지도 '" + keyword + "' 검색 상위 5곳에 없어요");
            }
        }

        int kakaoPts = 0;
        if (kk.measured() && kk.rank() != 0) {
            max += 4;
            if (kk.rank() > 0) {
                kakaoPts = kk.rank() <= 3 ? 4 : kk.rank() <= 7 ? 3 : 2;
                ev.add("카카오맵 '" + keyword + "' 검색 시 " + kk.rank() + "번째로 나와요");
            } else {
                ev.add("카카오맵 '" + keyword + "' 검색 상위 15곳에 없어요");
            }
        } else if (kk.note() != null) {
            ev.add(kk.note());
        }

        long mentions = estimatedMentions(blogSim, name);
        int blogPts = mentions == 0 ? 0 : mentions < 10 ? 2 : mentions < 50 ? 5 : mentions < 200 ? 7 : 8;
        ev.add("블로그 언급 약 " + mentions + "건");

        return new Axis("exposure", "찾기 쉬움", "손님이 찾기 쉬운 정도", "노출(SEO)", mapPts + kakaoPts + blogPts, max, true, ev);
    }

    /** 블로그 글이 메뉴·분위기 등을 설명하는지 — AI가 가게를 소개할 때 쓰는 재료 */
    private static final List<String> DESCRIPTIVE = List.of(
            "메뉴", "시그니처", "가격", "분위기", "인테리어", "좌석", "주차", "예약", "웨이팅",
            "디저트", "커피", "음료", "브런치", "추천", "영업시간", "콘센트", "반려", "단체");

    /**
     * AI가 찾기 쉬움 (10) — AI 노출 준비도(AEO). AI 검색이 가게를 소개할 때 참고하는 신호를 규칙으로 점수화.
     * (AI가 실제로 추천하는지는 답이 매번 달라 점수에 넣지 않고 "AI 언급률" 참고 지표로 따로 봄)
     *  - 네이버·카카오 정보 일치 3 (카카오 확인 못 하면 제외)
     *  - 홈페이지·SNS 링크 2
     *  - 방문자 리뷰 2 (사장님이 입력 안 하면 제외)
     *  - 블로그 글의 설명 풍부함 3
     */
    private Axis aiReadiness(LocalItem place, KakaoLookup kk, Integer reviews, BlogResponse blogSim, String name) {
        if (place == null) {
            return new Axis("aeo", "AI가 찾기 쉬움", "AI가 우리 가게를 소개하기 쉬운 정도", "AI 노출 준비도(AEO)",
                    0, 10, false, List.of("네이버에서 가게를 찾지 못해 확인하지 못했어요"));
        }
        List<String> ev = new ArrayList<>();
        int pts = 0, max = 0;

        if (kk.measured()) {
            max += 3;
            if (kk.place() == null) {
                ev.add("카카오맵에서 가게를 찾지 못했어요 — 등록하면 AI가 참고할 곳이 늘어요");
            } else if (sameAddress(place, kk.place())) {
                pts += 3;
                ev.add("네이버·카카오맵 주소가 같아요");
            } else {
                pts += 1;
                ev.add("네이버·카카오맵 주소가 서로 달라요 — 한쪽을 고치면 AI가 헷갈리지 않아요");
            }
        } else if (kk.note() != null) {
            ev.add(kk.note());
        }

        max += 2;
        boolean home = place.link() != null && !place.link().isBlank();
        if (home) pts += 2;
        ev.add("홈페이지·SNS 링크 " + (home ? "있음" : "없음"));

        if (reviews != null) {
            max += 2;
            pts += reviews >= 50 ? 2 : reviews >= 10 ? 1 : 0;
            ev.add("방문자 리뷰 " + reviews + "개");
        }

        max += 3;
        List<BlogItem> relevant = blogSim.items().stream().filter(b -> mentions(b, name)).toList();
        if (relevant.isEmpty()) {
            ev.add("가게 이름이 들어간 블로그 글이 없어요");
        } else {
            long rich = relevant.stream().filter(b -> {
                String t = NaverSearchClient.clean(b.title()) + " " + NaverSearchClient.clean(b.description());
                return DESCRIPTIVE.stream().filter(t::contains).count() >= 2;
            }).count();
            double ratio = (double) rich / relevant.size();
            pts += ratio >= 0.5 ? 3 : ratio >= 0.2 ? 2 : rich > 0 ? 1 : 0;
            ev.add("블로그 글 " + relevant.size() + "개 중 메뉴·분위기 등을 설명한 글 " + rich + "개 (" + Math.round(ratio * 100) + "%)");
        }

        return new Axis("aeo", "AI가 찾기 쉬움", "AI가 우리 가게를 소개하기 쉬운 정도", "AI 노출 준비도(AEO)",
                pts, max, true, ev);
    }

    /** 도로명 "OO로 12", "OO로47번길 12" (뒤에 붙는 "1층" 등은 무시) */
    private static final Pattern ROAD = Pattern.compile("([가-힣A-Za-z0-9]+(?:로|길))\\s*(\\d+(?:-\\d+)?)");
    /** 지번 "OO동 123-4" */
    private static final Pattern JIBUN = Pattern.compile("([가-힣0-9]+(?:동|리|가))\\s+(산?\\d+(?:-\\d+)?)");

    /**
     * 네이버·카카오 주소가 같은 곳인지 — 도로명(도로+건물번호) 또는 지번(동+번지)으로 비교.
     * "경기도"/"경기" 표기 차이, 층·호수("1층") 유무는 무시.
     */
    static boolean sameAddress(LocalItem n, KakaoPlace k) {
        String nr = key(ROAD, n.roadAddress()), kr = key(ROAD, k.roadAddressName());
        if (nr != null && nr.equals(kr)) return true;
        String nj = key(JIBUN, n.address()), kj = key(JIBUN, k.addressName());
        return nj != null && nj.equals(kj);
    }

    private static String key(Pattern p, String addr) {
        if (addr == null) return null;
        Matcher m = p.matcher(addr);
        return m.find() ? m.group(1) + " " + m.group(2) : null;
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
