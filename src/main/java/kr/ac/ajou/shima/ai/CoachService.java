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
        TASKS.put("qr_promo", "QR 도장 적립 알리기 — 계산대·테이블 안내문, 계산할 때 한마디 (적립 참여 늘리기)");
        TASKS.put("winback_message", "요즘 안 오신 단골에게 안부·재방문 혜택 알리기 (카톡 채널·SNS·다음 방문 때 인사)");
        TASKS.put("blog_review", "블로그 체험단·방문 후기 요청으로 블로그 언급 늘리기");
        TASKS.put("instagram_post", "인스타그램에 메뉴·분위기 게시물 올리기");
        TASKS.put("review_request", "방문 손님에게 네이버 영수증 리뷰 부탁 (계산대 안내문)");
        TASKS.put("naver_place_update", "네이버 플레이스 사진·메뉴·영업시간·소개글 보강");
        TASKS.put("kakao_place_update", "카카오맵 장소 정보(사진·메뉴·영업시간) 보강");
        TASKS.put("event", "재방문 이벤트 (예: 도장 2배 데이)");
        TASKS.put("time_promo", "원하는 시간대(예: 저녁) 손님 늘리기 — 그 시간대 한정 메뉴·혜택, 퇴근길·교대 시간 안내");
        TASKS.put("nearby_org", "근처 직장(병원·학교 등)과 연결 — 직원 대상 단체 주문·제휴 혜택, 직원 게시판·단톡 안내");
        TO_ACTION.put("qr_promo", "flyer");
        TO_ACTION.put("winback_message", "message");
        TO_ACTION.put("blog_review", "blog_review");
        TO_ACTION.put("instagram_post", "instagram_post");
        TO_ACTION.put("review_request", "other");
        TO_ACTION.put("naver_place_update", "naver_place");
        TO_ACTION.put("kakao_place_update", "other");
        TO_ACTION.put("event", "event");
        TO_ACTION.put("time_promo", "event");
        TO_ACTION.put("nearby_org", "other");
    }

    private static final String SYSTEM = """
            너는 동네 소상공인(카페·식당) 사장님을 돕는 마케팅 코치다.
            입력 JSON의 데이터만 근거로, 이번 주에 사장님이 할 일을 딱 1개 고른다.

            [사장님 고민·가게 특징 — 최우선]
            - ownerProfile.goal(고민·목표)이 있으면, 그 고민을 이번 주에 가장 직접 해결하는 할 일을 고른다.
              (예: "저녁 손님 늘리기" → time_promo 또는 저녁 시간을 겨냥한 다른 할 일, why와 steps에 저녁 시간대를 명시)
            - ownerProfile.notes의 제약(입지·테이크아웃 불가 등)에 맞지 않는 방법은 쓰지 않는다.
            - notes의 강점(예: 단골 충성도)을 활용하고, 주 손님층(예: 교직원·간호사)에 맞는 채널·시간·말투로 문구를 쓴다.
            - why 첫 문장은 사장님 고민을 짚고, 둘째 문장에 데이터 숫자(hourly·sources 등)를 근거로 든다.
            - notes에 없는 사정(근무 시간표, 주변 시설 이름 등)을 지어내지 않는다.

            [할 일 고르기]
            - task_type은 반드시 입력의 tasks 목록 key 중 하나.
            - (사장님 고민이 없거나 고민과 겹칠 때) improvements의 첫 항목(가장 부족한 점)을 해결하는 할 일을 우선 검토한다.
              고민이 있으면, 고민을 해결하면서 improvements도 함께 좋아지는 방법을 steps에 녹인다.
            - recentActions에 최근 2주 안에 한 종류, history에서 SKIPPED된 종류는 되도록 피한다.
            - (ownerProfile.goal이 없을 때) atRisk.count가 3 이상이면 winback_message를 우선 검토한다.
            - weeklyVisitors의 최근 4주 적립(new+revisit) 합이 20명 미만이면, 적립 자체를 알리는 qr_promo를 우선 검토한다.
              (도장 혜택을 알리는 내용이면 event가 아니라 qr_promo. event는 도장 2배 데이처럼 기간 한정 행사일 때만)

            [사실 정확성 — 가장 중요]
            - 숫자·순위·건수는 입력의 evidence 문장과 값에 적힌 그대로만 쓴다. 바꾸거나 추측하지 않는다.
              예: "카카오맵 '우만동 카페' 검색 시 10번째로 나와요" → "카카오맵에서는 10위" (보이지 않는다고 쓰면 틀림)
              "상위 N곳에 없어요"라고 적힌 경우에만 "보이지 않는다"고 쓴다.
            - 입력에 없는 사실(메뉴 이름, 가격, 원두, 수상, 위치 특징 등)을 지어내지 않는다.

            [문구 초안 — 할 일에 맞는 용도로]
            - qr_promo: 계산대·테이블에 둘 QR 적립 안내문 ("QR 찍고 전화번호만 넣으면 도장" + 리워드 규칙)
            - winback_message: 카톡 채널·인스타 스토리에 올릴 안부 글
            - blog_review: 계산대 후기 안내문 또는 체험단 모집 글
            - instagram_post: 인스타 게시물 캡션
            - review_request: 계산대에 둘 리뷰 부탁 안내문
            - naver_place_update / kakao_place_update: 지도 앱 '가게 소개' 칸에 넣을 소개글 (담백한 설명체)
            - event: 이벤트 안내 글
            - time_promo: 그 시간대 한정 혜택·메뉴 안내문 (매장 안내문·SNS용)
            - nearby_org: 근처 직장 직원 게시판·단톡에 올릴 안내 글 (정중한 말투)
            - 각 150자 이내, 3개는 서로 다른 톤(담백 / 친근 / 짧게).
            - 금지: "맛집", "최고", "1등", "유명한" 같은 자기 자랑·과장, 가격·할인 약속(사장님이 정하지 않은 혜택), 손님 개인정보.
            - 이모지는 문구당 1개까지.

            [나머지]
            - why: 데이터의 숫자를 1~2개 인용해 2문장 이내, 사장님께 말하듯 "~요"체. 인과 단정("~때문에 늘었다") 금지.
            - steps: 60대 사장님도 따라 할 수 있는 쉬운 말로 3개 이하, 각 40자 이내, "~하기" 또는 "~요"체.
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
    private final CustomerRepository customerRepo;
    private final ObjectMapper om;

    public CoachService(GeminiClient ai, StoreRepository storeRepository, CoachTaskRepository taskRepo,
                        FeedbackLogRepository feedbackRepo, MarketingActionRepository actionRepo,
                        RewardRuleRepository ruleRepo, DiagnosisService diagnosisService,
                        AiMentionService aiMentionService, StatsService statsService,
                        AtRiskService atRiskService, CustomerRepository customerRepo, ObjectMapper om) {
        this.customerRepo = customerRepo;
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
                GeminiClient.Answer a = ai.ask(SYSTEM, ctx.toPrettyString(), 3000, "medium"); // 사실 확인이 중요해 생각 수준 medium
                plan = parse(a.text());
                if (plan == null) log.warn(">> [coach] AI 응답 해석 실패 → 규칙 기반 대체: {}", cut(a.text(), 300));
            } catch (ExternalApiException e) {
                log.warn(">> [coach] AI 호출 실패 → 규칙 기반 대체: {}", e.getMessage());
            }
        }
        if (plan == null) {
            int recentStamps = statsService.weeklySeries(store, 4).stream()
                    .mapToInt(w -> w.newCount() + w.revisitCount()).sum();
            plan = rulePlan(store, diag.orElse(null), atRisk, history, recentStamps);
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

        Optional<AiMentionResponse> mention = aiMentionService.latest(store);
        mention.ifPresent(m -> {
            ObjectNode mn = c.putObject("aiMention");
            mn.put("asked", m.asked());
            mn.put("mentioned", m.mentioned());
            var miss = mn.putArray("notMentionedQuestions");
            m.items().stream().filter(i -> !i.mentioned()).forEach(i -> miss.add(i.question()));
        });

        // 진단·AI 언급률에서 더 좋아질 수 있는 점 (규칙으로 계산한 근거) — 할 일 선택의 근거
        var imp = c.putArray("improvements");
        for (CoachResponse.Improvement i : improvements(diag.orElse(null), mention.orElse(null))) {
            ObjectNode in = imp.addObject();
            in.put("area", i.area());
            in.put("status", i.status());
            in.put("relatedTask", i.taskType());
        }

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

        // 사장님 고민·가게 특징 (최우선)
        if (store.getOwnerGoal() != null || store.getStoreNotes() != null) {
            ObjectNode op = c.putObject("ownerProfile");
            op.put("goal", store.getOwnerGoal());
            op.put("notes", store.getStoreNotes());
        }

        // 시간대 (최근 4주 QR 적립, 하루 평균) — 저녁(17~21시) 비중 포함
        HourlyStats hs = statsService.hourly(store, 4);
        ObjectNode hn = c.putObject("hourly");
        hn.put("note", "QR 적립 시각 기준, 적립한 손님만");
        hn.set("weekday", hourSummary(hs, true));
        hn.set("weekend", hourSummary(hs, false));

        // 유입 경로 (최근 1년 첫 방문 손님)
        ObjectNode src = c.putObject("sources");
        for (Object[] row : customerRepo.countSourcesByFirstVisit(store, now.minus(Duration.ofDays(365)), now)) {
            src.put(row[0] == null ? "none" : (String) row[0], ((Number) row[1]).intValue());
        }

        ObjectNode tasks = c.putObject("tasks");
        TASKS.forEach(tasks::put);
        c.put("today", LocalDate.now(StatsService.KST).toString());
        return c;
    }

    private ObjectNode hourSummary(HourlyStats hs, boolean weekday) {
        int days = Math.max(1, weekday ? hs.weekdayDays() : hs.weekendDays());
        int total = 0, evening = 0, peakHour = -1, peakCnt = 0;
        for (HourlyStats.Hour h : hs.hours()) {
            int v = weekday ? h.weekdayTotal() : h.weekendTotal();
            total += v;
            if (h.hour() >= 17 && h.hour() <= 21) evening += v;
            if (v > peakCnt) { peakCnt = v; peakHour = h.hour(); }
        }
        ObjectNode n = om.createObjectNode();
        n.put("visitsPerDay", Math.round(total * 10.0 / days) / 10.0);
        n.put("peakHour", peakHour);
        n.put("eveningShare17to21", total == 0 ? null : Math.round(evening * 100.0 / total) + "%");
        return n;
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
    Plan rulePlan(Store store, DiagnosisResponse d, List<AtRiskCustomer> atRisk, List<CoachTask> history, int recentStamps) {
        Set<String> avoid = new HashSet<>();
        history.stream().limit(3).filter(t -> t.getStatus() == CoachTask.Status.SKIPPED).forEach(t -> avoid.add(t.getTaskType()));

        List<String> order = new ArrayList<>();
        String goal = store.getOwnerGoal() == null ? "" : store.getOwnerGoal();
        String notes = store.getStoreNotes() == null ? "" : store.getStoreNotes();
        if (goal.matches("(?s).*(저녁|오후|아침|점심|주말|시간).*")) order.add("time_promo"); // 사장님 고민 우선
        if ((goal + notes).matches("(?s).*(병원|학교|직장|교직원|간호|회사|직원).*")) order.add("nearby_org");
        if (recentStamps < 20) order.add("qr_promo");          // 적립이 아직 적으면 적립부터 알리기
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
            case "time_promo" -> new Plan(type, "저녁 시간 손님 늘리기",
                    "원하시는 시간대에 오실 이유를 하나 만들어 드리면 좋아요.",
                    List.of("저녁 시간 한정 혜택 하나 정하기", "계산대·SNS에 미리 알리기", "단골분께 먼저 말씀드리기"),
                    List.of("퇴근길에 들러 쉬어 가세요. 저녁 시간에도 편하게 머무르실 수 있어요 ☕",
                            "하루 마무리는 조용한 골목 카페에서. 저녁에도 문 열어 두었어요.",
                            "일 끝나고 한 잔 어떠세요? 저녁에도 기다리고 있을게요."));
            case "nearby_org" -> new Plan(type, "근처 직장 분들께 알리기",
                    "가까운 직장에서 오시는 분들이 많아요. 동료분들께도 알려지면 좋아요.",
                    List.of("자주 오시는 직원분께 안내문 부탁드리기", "직원 단체 주문 가능 여부 정하기", "다음 방문 때 감사 인사"),
                    List.of("가까운 곳에서 일하시는 분들 편하게 쉬어 가세요. 단체 주문도 미리 말씀해 주세요.",
                            "근무 중 잠깐의 휴식, 골목 안 조용한 카페에서 보내세요.",
                            "동료분들과 함께 오세요. 늘 찾아 주셔서 감사합니다."));
            case "qr_promo" -> new Plan(type, "QR 도장 적립 알리기",
                    "최근 4주 적립이 아직 적어요. 적립하는 손님이 많아야 단골을 알아볼 수 있어요.",
                    List.of("계산대·테이블에 QR 안내문 두기", "계산할 때 \"도장 찍어 드릴까요?\" 한마디", "첫 손님 몇 분은 직접 도와드리기"),
                    List.of("QR 찍고 전화번호만 넣으면 도장 적립! 앱 설치는 필요 없어요.",
                            "도장 모아 주세요 ☕ 휴대폰 카메라로 QR만 찍으면 돼요.",
                            "10초면 끝나는 도장 적립, 계산대 QR을 찍어 주세요."));
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
                t.getTitle(), p.why(), p.steps(), p.drafts(), audience, t.getChosenDraft(),
                improvements(diagnosisService.latest(store).orElse(null), aiMentionService.latest(store).orElse(null)));
    }

    // ======================= 더 좋아질 수 있는 점 (규칙) =======================

    /**
     * 진단 항목 중 만점의 85% 미만이거나 측정 못 한 항목 + AI 언급률이 3번 중 다 나오지 않은 경우.
     * 근거 문장은 진단 결과를 그대로 쓰고(AI가 숫자를 바꾸지 않게), 개선 방법은 항목별로 정해 둔 문장.
     * 부족한 점수가 큰 순서로 최대 5개.
     */
    List<CoachResponse.Improvement> improvements(DiagnosisResponse d, AiMentionResponse m) {
        record Gap(int gap, CoachResponse.Improvement item) {}
        List<Gap> out = new ArrayList<>();
        if (d != null) {
            for (DiagnosisResponse.Axis a : d.axes()) {
                String ev = String.join(" · ", a.evidence().stream().limit(3).toList());
                if (!a.measured()) {
                    if ("revisit".equals(a.key())) {
                        out.add(new Gap(a.max() / 2, new CoachResponse.Improvement(a.name(), "아직 측정 전 — " + ev,
                                "QR 적립 손님이 5명 이상 모이면 재방문을 잴 수 있어요. 계산대에서 적립을 권해 주세요.", "qr_promo")));
                    } else if ("reputation".equals(a.key())) {
                        out.add(new Gap(a.max() / 2, new CoachResponse.Improvement(a.name(), "측정 안 됨 — 방문자 리뷰 수 미입력",
                                "진단할 때 네이버 지도의 방문자 리뷰 수를 적어 주시면 함께 평가해요.", null)));
                    }
                    continue;
                }
                if (a.points() >= Math.ceil(a.max() * 0.85)) continue;
                String status = a.points() + " / " + a.max() + "점 — " + ev;
                CoachResponse.Improvement item = switch (a.key()) {
                    case "exposure" -> new CoachResponse.Improvement(a.name(), status,
                            "지도 앱 사진·소개글을 채우고, 블로그 후기를 늘리면 검색에서 더 잘 보여요.", "blog_review");
                    case "aeo" -> new CoachResponse.Improvement(a.name(), status,
                            "네이버·카카오 정보를 똑같이 맞추고, 메뉴·분위기·위치를 설명하는 글을 늘리면 AI가 소개하기 쉬워요.", "kakao_place_update");
                    case "reputation" -> new CoachResponse.Improvement(a.name(), status,
                            "계산대에 영수증 리뷰 부탁 안내문을 두면 리뷰가 꾸준히 늘어요.", "review_request");
                    case "activity" -> new CoachResponse.Improvement(a.name(), status,
                            "인스타·블로그에 가게 소식을 주 1회 올리면 '살아 있는 가게'로 보여요.", "instagram_post");
                    case "completeness" -> new CoachResponse.Improvement(a.name(), status,
                            "네이버 플레이스에 빠진 정보(주소·업종·홈페이지/SNS)를 채워 주세요.", "naver_place_update");
                    case "revisit" -> new CoachResponse.Improvement(a.name(), status,
                            "요즘 안 오신 단골께 안부를 전하거나 재방문 혜택을 알려 보세요.", "winback_message");
                    default -> null;
                };
                if (item != null) out.add(new Gap(a.max() - a.points(), item));
            }
        }
        if (m != null && m.mentioned() < m.asked()) {
            List<String> missed = m.items().stream().filter(i -> !i.mentioned()).map(i -> "\"" + i.question() + "\"").toList();
            out.add(new Gap((m.asked() - m.mentioned()) * 3, new CoachResponse.Improvement("AI 언급률",
                    "AI에게 " + m.asked() + "번 물어 " + m.mentioned() + "번 추천됨 — 빠진 질문: " + String.join(", ", missed),
                    "블로그·지도에 '어떤 손님에게 좋은 곳인지'(분위기, 대표 메뉴, 위치)를 구체적으로 적은 글이 늘면 일반 추천에도 나오기 쉬워요.",
                    "blog_review")));
        }
        out.sort(Comparator.comparingInt(Gap::gap).reversed());
        return out.stream().limit(5).map(Gap::item).toList();
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
