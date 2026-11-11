package kr.ac.ajou.shima.controller;

import jakarta.validation.Valid;
import kr.ac.ajou.shima.dto.CustomerSummary;
import kr.ac.ajou.shima.dto.DiagnosisRequest;
import kr.ac.ajou.shima.dto.DiagnosisResponse;
import org.springframework.http.ResponseEntity;
import kr.ac.ajou.shima.dto.GrantRequest;
import kr.ac.ajou.shima.dto.QrResponse;
import kr.ac.ajou.shima.dto.StatsSummary;
import kr.ac.ajou.shima.dto.WeekPoint;
import kr.ac.ajou.shima.dto.WeeklyReport;
import org.springframework.format.annotation.DateTimeFormat;
import kr.ac.ajou.shima.service.OwnerService;
import kr.ac.ajou.shima.service.PocService;
import kr.ac.ajou.shima.service.MarketingService;
import kr.ac.ajou.shima.ai.CoachService;
import kr.ac.ajou.shima.dto.CoachResponse;
import kr.ac.ajou.shima.dto.MarketingActionRequest;
import kr.ac.ajou.shima.dto.MarketingActionResponse;
import kr.ac.ajou.shima.dto.DailyCountRequest;
import kr.ac.ajou.shima.dto.DailyCountResponse;
import kr.ac.ajou.shima.dto.PocMetrics;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 사장님 API (P0) — AuthInterceptor가 JWT로 보호, 로그인한 사장님의 매장 기준 */
@RestController
@RequestMapping("/api/v1/owner")
public class OwnerController {

    private final OwnerService ownerService;
    private final kr.ac.ajou.shima.ratelimit.RateLimiter rateLimiter;
    private final PocService pocService;
    private final MarketingService marketingService;
    private final CoachService coachService;
    private final kr.ac.ajou.shima.ai.WriterService writerService;

    public OwnerController(OwnerService ownerService, kr.ac.ajou.shima.ratelimit.RateLimiter rateLimiter,
                           PocService pocService, MarketingService marketingService, CoachService coachService,
                           kr.ac.ajou.shima.ai.WriterService writerService) {
        this.writerService = writerService;
        this.coachService = coachService;
        this.ownerService = ownerService;
        this.rateLimiter = rateLimiter;
        this.pocService = pocService;
        this.marketingService = marketingService;
    }

    // ===== AI 처방 (F3) — 이번 주 할 일 =====

    /** 가장 최근 추천 — 없으면 204 */
    @GetMapping("/coach/current")
    public ResponseEntity<CoachResponse> coachCurrent() {
        return coachService.current().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** 새 추천 받기 (AI 실패 시 규칙 기반) — 하루 5번 */
    @PostMapping("/coach")
    public CoachResponse coachNew() {
        rateLimiter.hit("coach:" + kr.ac.ajou.shima.auth.CurrentOwner.id(), 5, java.time.Duration.ofDays(1),
                "새 추천은 하루 5번까지 받을 수 있어요.");
        return coachService.generate();
    }

    /** 했어요 — {"chosenDraft": 0~2 | null} → 마케팅 기록 자동 추가 */
    @PostMapping("/coach/{id}/done")
    public CoachResponse coachDone(@PathVariable Long id, @RequestBody(required = false) Map<String, Integer> body) {
        return coachService.done(id, body == null ? null : body.get("chosenDraft"));
    }

    /** 이번엔 건너뛸게요 — 다음 추천에서 같은 종류를 피함 */
    @PostMapping("/coach/{id}/skip")
    public CoachResponse coachSkip(@PathVariable Long id) {
        return coachService.skip(id);
    }

    // ===== SNS 글 대신 쓰기 =====

    /** 인스타 캡션 / 블로그 글 — 하루 20번 */
    @PostMapping("/write")
    public kr.ac.ajou.shima.dto.WriteResponse write(@Valid @RequestBody kr.ac.ajou.shima.dto.WriteRequest request) {
        rateLimiter.hit("write:" + kr.ac.ajou.shima.auth.CurrentOwner.id(), 20, java.time.Duration.ofDays(1),
                "글 쓰기는 하루 20번까지 할 수 있어요.");
        return writerService.write(request);
    }

    // ===== 마케팅 실행 기록 (그래프 오버레이) =====

    /** 기간 내 기록 (기본: 최근 26주) */
    @GetMapping("/actions")
    public List<MarketingActionResponse> actions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return marketingService.list(from, to);
    }

    @PostMapping("/actions")
    public MarketingActionResponse addAction(@Valid @RequestBody MarketingActionRequest request) {
        return marketingService.add(request);
    }

    @DeleteMapping("/actions/{id}")
    public ResponseEntity<Void> deleteAction(@PathVariable Long id) {
        marketingService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ===== PoC 측정 =====

    /** 하루 전체 손님 수 조회 (date 없으면 오늘) */
    @GetMapping("/daily-visitors")
    public DailyCountResponse dailyVisitors(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return pocService.getDailyCount(date);
    }

    /** 하루 전체 손님 수 입력/수정 (영수증 건수) — 적립 참여율의 분모 */
    @PutMapping("/daily-visitors")
    public DailyCountResponse setDailyVisitors(@Valid @RequestBody DailyCountRequest request) {
        return pocService.setDailyCount(request);
    }

    /** 사장님 사용 기록 — {"type":"REPORT_VIEW"} / {"type":"DIAGNOSIS_VIEW"} */
    @PostMapping("/events")
    public ResponseEntity<Void> event(@RequestBody Map<String, String> body) {
        pocService.recordEvent(body.get("type"));
        return ResponseEntity.noContent().build();
    }

    /** PoC 성공 지표 (기본: 최근 14일) */
    @GetMapping("/poc")
    public PocMetrics poc(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return pocService.metrics(from, to);
    }

    @GetMapping("/stats/summary")
    public StatsSummary summary() {
        return ownerService.summary();
    }

    /** 온라인 진단 실행 — body 전부 선택 (상호명·지역·방문자 리뷰 수) */
    @PostMapping("/diagnosis")
    public DiagnosisResponse runDiagnosis(@Valid @RequestBody(required = false) DiagnosisRequest request) {
        // 네이버 API 사용량 보호 (진단 1회 = 네이버 호출 4회)
        rateLimiter.hit("diagnosis:" + kr.ac.ajou.shima.auth.CurrentOwner.id(), 10, java.time.Duration.ofHours(1),
                "진단은 한 시간에 10번까지 할 수 있어요. 잠시 후 다시 해 주세요.");
        return ownerService.runDiagnosis(request);
    }

    /** AI 언급률 확인 — 웹 검색 AI에게 "동네+업종 추천"을 3번 물어 우리 가게가 나오는지 (참고 지표, 30~60초) */
    @PostMapping("/diagnosis/ai-check")
    public kr.ac.ajou.shima.dto.AiMentionResponse aiCheck() {
        // AI API 비용 보호 (Gemini 토큰 + 구글 검색 무료 한도)
        rateLimiter.hit("ai-check:" + kr.ac.ajou.shima.auth.CurrentOwner.id(), 3, java.time.Duration.ofDays(1),
                "AI 확인은 하루 3번까지 할 수 있어요. 내일 다시 해 주세요.");
        return ownerService.checkAiMentions();
    }

    /** 최근 AI 언급률 — 한 번도 안 했으면 204 */
    @GetMapping("/diagnosis/ai-check/latest")
    public ResponseEntity<kr.ac.ajou.shima.dto.AiMentionResponse> latestAiCheck() {
        return ownerService.latestAiMentions().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** 최근 진단 결과 — 한 번도 안 했으면 204 */
    @GetMapping("/diagnosis/latest")
    public ResponseEntity<DiagnosisResponse> latestDiagnosis() {
        return ownerService.latestDiagnosis().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** 최근 N주 방문 추이 (기본 8주, 최대 26주) */
    /** 시간대별 방문 — QR 적립 시각, 1시간 단위, 평일·주말 따로 (기본 최근 4주) */
    @GetMapping("/stats/hourly")
    public kr.ac.ajou.shima.dto.HourlyStats hourly(@RequestParam(defaultValue = "4") int weeks) {
        return ownerService.hourly(weeks);
    }

    @GetMapping("/stats/weekly")
    public List<WeekPoint> weekly(@RequestParam(defaultValue = "8") int weeks) {
        return ownerService.weeklySeries(weeks);
    }

    /** 주간 리포트 — weekStart(YYYY-MM-DD, 그 주의 아무 날짜) 없으면 이번 주 */
    @GetMapping("/reports/weekly")
    public WeeklyReport weeklyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStart) {
        return ownerService.weeklyReport(weekStart);
    }

    /** 이탈 위험 단골 — 오래 안 온 순 */
    @GetMapping("/customers/at-risk")
    public List<kr.ac.ajou.shima.dto.AtRiskCustomer> atRisk(@RequestParam(defaultValue = "50") int limit) {
        return ownerService.atRiskCustomers(limit);
    }

    @GetMapping("/customers/today")
    public List<CustomerSummary> today() {
        return ownerService.todayVisitors();
    }

    @GetMapping("/customers")
    public List<CustomerSummary> customers() {
        return ownerService.customers();
    }

    /** 태블릿용 QR — 30초마다 폴링, 토큰은 서버가 알아서 교체 */
    /** 손님 데이터 삭제 — 복구 불가 */
    @DeleteMapping("/customers/{customerId}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable Long customerId) {
        ownerService.deleteCustomer(customerId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/qr")
    public QrResponse qr() {
        return ownerService.rotatingQr();
    }

    /** 인쇄용 QR (장기 토큰) */
    @GetMapping("/qr/print")
    public QrResponse printQr() {
        return ownerService.printQr();
    }

    @PostMapping("/customers/{id}/grant")
    public Map<String, Object> grant(@PathVariable Long id, @Valid @RequestBody GrantRequest request) {
        ownerService.grant(id, request);
        return Map.of("granted", true);
    }
}
