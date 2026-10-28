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

    public OwnerController(OwnerService ownerService, kr.ac.ajou.shima.ratelimit.RateLimiter rateLimiter,
                           PocService pocService) {
        this.ownerService = ownerService;
        this.rateLimiter = rateLimiter;
        this.pocService = pocService;
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

    /** 최근 진단 결과 — 한 번도 안 했으면 204 */
    @GetMapping("/diagnosis/latest")
    public ResponseEntity<DiagnosisResponse> latestDiagnosis() {
        return ownerService.latestDiagnosis().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** 최근 N주 방문 추이 (기본 8주, 최대 26주) */
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
