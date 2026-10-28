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
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 사장님 API (P0) — AuthInterceptor가 JWT로 보호, 로그인한 사장님의 매장 기준 */
@RestController
@RequestMapping("/api/v1/owner")
public class OwnerController {

    private final OwnerService ownerService;

    public OwnerController(OwnerService ownerService) {
        this.ownerService = ownerService;
    }

    @GetMapping("/stats/summary")
    public StatsSummary summary() {
        return ownerService.summary();
    }

    /** 온라인 진단 실행 — body 전부 선택 (상호명·지역·방문자 리뷰 수) */
    @PostMapping("/diagnosis")
    public DiagnosisResponse runDiagnosis(@Valid @RequestBody(required = false) DiagnosisRequest request) {
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
