package kr.ac.ajou.shima.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.ac.ajou.shima.auth.CurrentOwner;
import kr.ac.ajou.shima.diagnosis.DiagnosisService;
import kr.ac.ajou.shima.diagnosis.ExternalApiException;
import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.dto.*;
import kr.ac.ajou.shima.repository.*;
import kr.ac.ajou.shima.service.AtRiskService;
import kr.ac.ajou.shima.service.StatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * AI 처방 (F3) — 진단·통계·이탈 단골·마케팅 기록·지난 선택을 모아 "이번 주 할 일" 1개 + 문구 초안 3개를 만든다.
 *
 *  - AI(Gemini, 검색 없음)에는 가게 단위 숫자만 보낸다 — 손님 전화번호·이름은 보내지 않음
 *  - 할 일은 TASKS 목록 중에서만 고르게 하고, 응답이 이상하면 규칙 기반 추천으로 대체 (시연 중 끊기지 않게)
 *  - "했어요" → 마케팅 기록 자동 추가(source=F3, 그래프 ◆) + 피드백 기록 / "건너뛸게요" → 다음 추천에서 피함
 *  - 표현 원칙: 같은 시기 비교까지만. "○○ 때문에 늘었다" 같은 인과 단정 금지
 */
@Service
public class CoachService {

    private static final Logger log = LoggerFactory.getLogger(CoachService.class);

    /** 할 일 종류 — 사장님 앱 coach.ts TASK_LABEL과 맞출 것 */
    static final Map<String, String> TASKS = new LinkedHashMap<>();
    /** 할 일 → 마케팅 기록 종류 (MarketingService.TYPES) */
    private static final Map<String, String> TO_ACTION = new HashMap<>();
    static {
        TASKS.put("winback_message", "요즘 안 오신 단골에게 안부·재방문 혜택 알리기 (카톡 채널·SNS·다음 방문 때 인사)");
        TASKS.put("blog_review", "블로그 체험단·방문 후기 요청으로 블로그 언급 늘리기");
        TASKS.put("instagram_post", "인스타그램에 메뉴·분위기 게시물 올리기");
        TASKS.put("review_request", "방문 손님에게 네이버 영수증 리뷰 부탁 (계산대 안내문)");
        TASKS.put("naver_place_update", "네이버 플레이스 사진·메뉴·영업시간·소개글 보강");
        TASKS.put("kakao_place_update", "카카오맵 장소 정보(사진·메뉴·영업시간) 보강");
        TASKS.put("event", "재방문 이벤트 (예: 도장 2배 데이)");
        TO_ACTION.put("winback_message", "message");
        TO_ACTION.put("blog_review", "blog_review");
        TO_ACTION.put("instagram_post", "instagram_post");
        TO_ACTION.put("review_request", "other");
        TO_ACTION.put("naver_place_update", "naver_place");
        TO_ACTION.put("kakao_place_update", "other");
        TO_ACTION.put("event", "event");
    }

    private static final String SYSTEM = """
            너는 동네 소상공인(카페·식당) 사장님을 돕는 마케팅 코치다.
            입력 JSON의 데이터만 근거로, 이번 주에 사장님이 할 일을 딱 1개 고른다.
            규칙:
            - task_type은 반드시 입력의 tasks 목록 key 중 하나.
            - recentActions에 최근 2주 안에 한 종류, history에서 SKIPPED된 종류는 되도록 피한다.
            - atRisk.count가 3 이상이면 winback_message를 우선 검토한다.
            - why: 데이터의 숫자를 1~2개 인용해 2문장 이내. "○○ 때문에 늘었다" 같은 인과 단정은 쓰지 않는다.
            - steps: 60대 사장님도 따라 할 수 있는 쉬운 말로 3개 이하, 각 40자 이내.
            - drafts: 사장님이 그대로 복사해 쓸 문구 3개 (할 일에 맞게 인스타 캡션·카톡 안내·매장 안내문 등), 각 150자 이내.
              과장·허위·가격 약속 금지, 이모지는 1~2개까지, 손님 개인정보를 만들어내지 않는다.
            - title: 이번 주 할 일 한 줄 (25자 이내, "~하기"로 끝남).
            출력은 JSON 객체 하나만 (설명·코드블록 없이):
            {"task_type":"","title":"","why":"","steps":["",""],"drafts":["","",""]}""";

    private final GeminiClient ai;
    private final StoreRepository storeRepository;
    private final CoachTaskRepository taskRepo;
    private final FeedbackLogRepository feedbackRepo;
    private final MarketingActionRepository actionRepo;
    private final RewardRuleRepository ruleRepo;
    private final DiagnosisService diagnosisService;
    private final AiMentionService aiMentionService;
    private final StatsService statsService;
    private final AtRiskService atRiskService;
    private final ObjectMapper om;

    public CoachService(GeminiClient ai, StoreRepository storeRepository, CoachTaskRepository taskRepo,
                        FeedbackLogRepository feedbackRepo, MarketingActionRepository actionRepo,
                        RewardRuleRepository ruleRepo, DiagnosisService diagnosisService,
                        AiMentionService aiMentionService, StatsService statsService,
                        AtRiskService atRiskService, ObjectMapper om) {
        this.ai = ai;
        this.storeRepository = storeRepository;
        this.taskRepo = taskRepo;
        this.feedbackRepo = feedbackRepo;
        this.actionRepo = actionRepo;
        this.ruleRepo = ruleRepo;
        this.diagnosisService = diagnosisService;
        this.aiMentionService = aiMentionService;
        this.statsService = statsService;
        this.atRiskService = atRiskService;
        this.om = om;
    }

    private Store currentStore() {
        return storeRepository.findFirstByOwner_Id(CurrentOwner.id())
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
    }

    // ======================= 조회 =======================

    @Transactional(readOnly = true)
    public Optional<CoachResponse> current() {
        Store store = currentStore();
        return taskRepo.findFirstByStoreOrderByCreatedAtDesc(store).map(t -> toResponse(t, store));
    }

    // ======================= 새 추천 =======================

    /** 트랜잭션 없음 — AI 응답을 기다리는 동안 DB 연결을 잡지 않도록 (조회·저장은 각자 짧게) */
    public CoachResponse generate() {
        Store store = currentStore();
        List<AtRiskCustomer> atRisk = atRiskService.list(store, 50);
        List<CoachTask> history = taskRepo.findTop10ByStoreOrderByCreatedAtDesc(store);
        Optional<DiagnosisResponse> diag = diagnosisService.latest(store);

        ObjectNode ctx = buildContext(store, diag, atRisk, history);

        Plan plan = null;
        String source = "AI", model = ai.model();
        if (ai.isConfigured()) {
            try {
                GeminiClient.Answer a = ai.ask(SYSTEM, ctx.toPrettyString(), 2000);
                plan = parse(a.text());
                if (plan == null) log.warn(">> [coach] AI 응답 해석 실패 → 규칙 기반 대체: {}", cut(a.text(), 300));
            } catch (ExternalApiException e) {
                log.warn(">> [coach] AI 호출 실패 → 규칙 기반 대체: {}", e.getMessage());
            }
        }
        if (plan == null) {
            plan = rulePlan(diag.orElse(null), atRisk, history);
            source = "RULE";
            model = null;
        }

        CoachTask saved = taskRepo.save(new CoachTask(store, plan.taskType(), plan.title(), source, model, toJson(plan)));
        return toResponse(saved, store);
    }

    // ======================= 사장님 선택 =======================

    @Transactional
    public CoachResponse done(Long id, Integer chosenDraft) {
        Store store = currentStore();
        CoachTask t = taskRepo.findByIdAndStore(id, store).orElseThrow(() -> new IllegalArgumentException("추천을 찾을 수 없어요."));
        if (t.getStatus() != CoachTask.Status.PENDING) throw new IllegalArgumentException("이미 처리한 추천이에요.");
        Plan p = fromJson(t.getPayloadJson());
        Integer chosen = chosenDraft != null && chosenDraft >= 0 && chosenDraft < p.drafts().size() ? chosenDraft : null;
        t.done(chosen);
        feedbackRepo.save(new FeedbackLog(store, String.valueOf(t.getId()), chosen == null ? null : "draft:" + chosen, false));
        // 그래프 ◆ 표시용 마케팅 기록 자동 추가 (AI 추천 수행)
        actionRepo.save(new MarketingAction(store, TO_ACTION.getOrDefault(t.getTaskType(), "other"),
                ActionSource.F3, Instant.now(), cut(t.getTitle(), 100)));
        return toResponse(t, store);
    }

    @Transactional
    public CoachResponse skip(Long id) {
        Store store = currentStore();
        CoachTask t = taskRepo.findByIdAndStore(id, store).orElseThrow(() -> new IllegalArgumentException("추천을 찾을 수 없어요."));
        if (t.getStatus() != CoachTask.Status.PENDING) throw new IllegalArgumentException("이미 처리한 추천이에요.");
        t.skip();
        feedbackRepo.save(new FeedbackLog(store, String.valueOf(t.getId()), null, true));
        return toResponse(t, store);
    }

    // ======================= AI 입력 =======================

    private ObjectNode buildContext(Store store, Optional<DiagnosisResponse> diag, List<AtRiskCustomer> atRisk,
                                    List<CoachTask> history) {
        ObjectNode c = om.createObjectNode();
        ObjectNode s = c.putObject("store");
        s.put("name", store.getName());
        s.put("region", store.getRegion());
        ruleRepo.findFirstByStoreAndActiveTrue(store)
                .ifPresent(r -> s.put("stampReward", "도장 " + r.getStampGoal() + "개 → " + r.getRewardName()));

        diag.ifPresent(d -> {
            ObjectNode dn = c.putObject("diagnosis");
            dn.put("score", d.totalScore());
            dn.put("searchKeyword", d.searchKeyword());
            var axes = dn.putArray("axes");
            for (DiagnosisResponse.Axis a : d.axes()) {
                ObjectNode an = axes.addObject();
                an.put("name", a.name());
                an.put("measured", a.measured());
                an.put("points", a.points());
                an.put("max", a.max());
                var ev = an.putArray("evidence");
                a.evidence().forEach(ev::add);
            }
            if (d.peers() != null) {
                var pa = dn.putArray("peers");
                for (DiagnosisResponse.Peer p : d.peers()) {
                    ObjectNode pn = pa.addObject();
                    pn.put("name", p.self() ? "(우리 가게)" : p.name());
                    pn.put("blogMentions", p.blogMentions());
                    pn.put("recentPosts30", p.recentPosts30());
                }
            }
        });

        aiMentionService.latest(store).ifPresent(m -> {
            ObjectNode mn = c.putObject("aiMention");
            mn.put("asked", m.asked());
            mn.put("mentioned", m.mentioned());
        });

        var weeks = c.putArray("weeklyVisitors");
        for (WeekPoint w : statsService.weeklySeries(store, 4)) {
            ObjectNode wn = weeks.addObject();
            wn.put("weekStart", w.weekStart().toString());
            wn.put("new", w.newCount());
            wn.put("revisit", w.revisitCount());
            wn.put("inProgress", w.inProgress());
        }

        // 이탈 단골 — 개인정보 없이 숫자만
        ObjectNode ar = c.putObject("atRisk");
        ar.put("count", atRisk.size());
        var ex = ar.putArray("examples");
        atRisk.stream().limit(5).forEach(a -> {
            ObjectNode an = ex.addObject();
            an.put("visits", a.visitCount());
            an.put("usualCycleDays", Math.round(a.avgCycleDays()));
            an.put("daysSinceLast", a.daysSinceLast());
        });

        Instant now = Instant.now();
        var acts = c.putArray("recentActions");
        for (MarketingAction a : actionRepo.findByStoreAndActedAtBetweenOrderByActedAtAsc(store, now.minus(Duration.ofDays(28)), now)) {
            ObjectNode an = acts.addObject();
            an.put("date", a.getActedAt().atZone(StatsService.KST).toLocalDate().toString());
            an.put("type", a.getActionType());
        }

        var hist = c.putArray("history");
        for (CoachTask t : history) {
            ObjectNode hn = hist.addObject();
            hn.put("date", t.getCreatedAt().atZone(StatsService.KST).toLocalDate().toString());
            hn.put("taskType", t.getTaskType());
            hn.put("status", t.getStatus().name());
        }

        ObjectNode tasks = c.putObject("tasks");
        TASKS.forEach(tasks::put);
        c.put("today", LocalDate.now(StatsService.KST).toString());
        return c;
    }

    // ======================= AI 출력 해석 =======================

    /** 저장·응답용 내부 형태 */
    public record Plan(String taskType, String title, String why, List<String> steps, List<String> drafts) {}

    /** JSON 객체 하나를 찾아 검증 — 형식이 어긋나면 null (규칙 기반으로 대체) */
    Plan parse(String text) {
        if (text == null) return null;
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = om.readTree(text.substring(a, b + 1));
            String type = n.path("task_type").asText("");
            if (!TASKS.containsKey(type)) return null;
            String title = cut(n.path("title").asText(""), 60);
            String why = cut(n.path("why").asText(""), 300);
            List<String> steps = strings(n.path("steps"), 3, 120);
            List<String> drafts = strings(n.path("drafts"), 3, 400);
            if (title.isBlank() || drafts.isEmpty()) return null;
            return new Plan(type, title, why, steps, drafts);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static List<String> strings(JsonNode arr, int max, int len) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : arr) {
            String v = cut(x.asText(""), len);
            if (!v.isBlank()) out.add(v);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String cut(String s, int len) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > len ? t.substring(0, len) : t;
    }

    // ======================= 규칙 기반 대체 =======================

    /**
     * AI를 못 쓸 때: 이탈 단골 3명↑ → 안부 / 아니면 가장 약한 진단 항목에 맞는 할 일.
     * 최근 건너뛴 종류는 피함.
     */
    Plan rulePlan(DiagnosisResponse d, List<AtRiskCustomer> atRisk, List<CoachTask> history) {
        Set<String> avoid = new HashSet<>();
        history.stream().limit(3).filter(t -> t.getStatus() == CoachTask.Status.SKIPPED).forEach(t -> avoid.add(t.getTaskType()));

        List<String> order = new ArrayList<>();
        if (atRisk.size() >= 3) order.add("winback_message");
        if (d != null) {
            d.axes().stream().filter(DiagnosisResponse.Axis::measured)
                    .sorted(Comparator.comparingDouble(a -> (double) a.points() / Math.max(1, a.max())))
                    .forEach(a -> order.add(switch (a.key()) {
                        case "exposure" -> "blog_review";
                        case "aeo" -> "kakao_place_update";
                        case "reputation" -> "review_request";
                        case "activity" -> "instagram_post";
                        case "completeness" -> "naver_place_update";
                        case "revisit" -> "event";
                        default -> "instagram_post";
                    }));
        }
        order.add("instagram_post");
        String type = order.stream().filter(t -> !avoid.contains(t)).findFirst().orElse("instagram_post");
        return template(type, atRisk.size());
    }

    private static Plan template(String type, int atRiskCount) {
        return switch (type) {
            case "winback_message" -> new Plan(type, "요즘 안 오신 단골께 안부 전하기",
                    "평소보다 한참 안 오신 단골이 " + atRiskCount + "분 있어요.",
                    List.of("아래 문구 중 하나를 고르기", "카톡 채널·인스타 스토리에 올리기", "다음에 오시면 반갑게 인사하기"),
                    List.of("요즘 뜸하셨죠? 다시 오시면 도장 하나 더 찍어 드릴게요 ☕",
                            "오랜만에 들러 주세요! 이번 주 오시는 단골분께 작은 서비스 준비했어요.",
                            "보고 싶었어요 😊 새로 나온 메뉴도 있으니 편하게 들러 주세요."));
            case "blog_review" -> new Plan(type, "블로그 후기 부탁하기",
                    "블로그에 우리 가게 이야기가 주변 가게보다 적어요.",
                    List.of("계산대에 후기 안내문 붙이기", "후기 남긴 손님께 작은 서비스", "체험단 1~2명 초대해 보기"),
                    List.of("블로그에 후기 남겨 주시면 다음 방문 때 음료 사이즈업 해 드려요!",
                            "오늘 드신 메뉴, 블로그에 소개해 주시면 큰 힘이 돼요 🙏",
                            "체험단 모집: 우리 가게 메뉴를 소개해 주실 블로거님을 찾아요."));
            case "review_request" -> new Plan(type, "영수증 리뷰 부탁하기",
                    "리뷰가 많을수록 손님이 믿고 찾아와요.",
                    List.of("계산대에 리뷰 안내문 두기", "계산할 때 한마디 건네기", "리뷰 남긴 분께 감사 인사"),
                    List.of("네이버 영수증 리뷰 남겨 주시면 큰 힘이 돼요 😊",
                            "오늘 맛있게 드셨다면 리뷰 한 줄 부탁드려요!",
                            "리뷰 남겨 주신 손님께 다음 방문 때 작은 서비스 드려요."));
            case "naver_place_update" -> new Plan(type, "네이버 플레이스 정보 채우기",
                    "가게 정보가 빠짐없으면 손님이 찾기 쉬워요.",
                    List.of("대표 메뉴 사진 3장 올리기", "영업시간·휴무일 확인하기", "가게 소개글 두세 줄 쓰기"),
                    List.of("직접 내린 커피와 편안한 자리가 있는 동네 카페입니다.",
                            "공부·모임 하기 좋은 넓은 자리, 콘센트도 있어요.",
                            "매일 아침 새로 굽는 디저트와 함께 쉬어 가세요."));
            case "kakao_place_update" -> new Plan(type, "카카오맵 가게 정보 채우기",
                    "카카오맵에서도 잘 보여야 AI와 손님이 우리 가게를 찾기 쉬워요.",
                    List.of("카카오맵에서 우리 가게 찾기", "사진·메뉴·영업시간 수정 요청", "네이버와 같은 정보로 맞추기"),
                    List.of("직접 내린 커피와 편안한 자리가 있는 동네 카페입니다.",
                            "대표 메뉴: 시그니처 라떼, 수제 디저트",
                            "영업시간과 휴무일은 매장 공지를 확인해 주세요."));
            case "event" -> new Plan(type, "도장 2배 데이 열기",
                    "다시 오는 손님을 늘리는 데 도장 혜택이 효과적이에요.",
                    List.of("이번 주 하루를 이벤트 날로 정하기", "매장·인스타에 미리 알리기", "당일 도장 2개씩 찍어 드리기"),
                    List.of("이번 주 금요일은 도장 2배 데이! 한 번 오시면 두 개 찍어 드려요 🎉",
                            "단골분들 감사해요. 이번 주 하루, 도장을 두 배로 드립니다.",
                            "도장 모으시는 분들 주목! 이번 주 깜짝 2배 이벤트 해요."));
            default -> new Plan("instagram_post", "인스타에 오늘 메뉴 올리기",
                    "꾸준한 소식이 가게가 살아 있다는 신호가 돼요.",
                    List.of("오늘 메뉴 사진 한 장 찍기", "아래 문구 중 하나 붙이기", "가게 위치 태그 달기"),
                    List.of("오늘도 정성껏 내린 커피 한 잔 ☕ 편하게 쉬어 가세요.",
                            "새로 나온 메뉴, 맛보러 오세요! 오늘만의 한 잔을 준비했어요.",
                            "창가 자리에서 여유 한 잔 어떠세요? 오늘도 문 열었어요 😊"));
        };
    }

    // ======================= 변환 =======================

    private CoachResponse toResponse(CoachTask t, Store store) {
        Plan p = fromJson(t.getPayloadJson());
        List<AtRiskCustomer> audience = "winback_message".equals(t.getTaskType())
                ? atRiskService.list(store, 20) : List.of();
        return new CoachResponse(t.getId(), t.getCreatedAt(), t.getStatus().name(), t.getSource(), t.getTaskType(),
                t.getTitle(), p.why(), p.steps(), p.drafts(), audience, t.getChosenDraft());
    }

    private String toJson(Plan p) {
        try { return om.writeValueAsString(p); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    private Plan fromJson(String s) {
        try { return om.readValue(s, Plan.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
