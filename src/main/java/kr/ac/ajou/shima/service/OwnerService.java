package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.dto.CustomerSummary;
import kr.ac.ajou.shima.diagnosis.DiagnosisService;
import kr.ac.ajou.shima.dto.DiagnosisRequest;
import kr.ac.ajou.shima.dto.DiagnosisResponse;
import kr.ac.ajou.shima.dto.GrantRequest;
import kr.ac.ajou.shima.dto.QrResponse;
import kr.ac.ajou.shima.dto.StatsSummary;
import kr.ac.ajou.shima.dto.WeekPoint;
import kr.ac.ajou.shima.dto.WeeklyReport;
import kr.ac.ajou.shima.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OwnerService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final VisitLogRepository visitLogRepository;
    private final RewardGrantRepository rewardGrantRepository;
    private final CustomerDataService customerDataService;
    private final QrTokenService qrTokenService;
    private final StatsService statsService;
    private final DiagnosisService diagnosisService;

    public OwnerService(StoreRepository storeRepository, CustomerRepository customerRepository,
                        VisitLogRepository visitLogRepository, RewardGrantRepository rewardGrantRepository,
                        QrTokenService qrTokenService, StatsService statsService,
                        DiagnosisService diagnosisService, CustomerDataService customerDataService) {
        this.customerDataService = customerDataService;
        this.statsService = statsService;
        this.diagnosisService = diagnosisService;
        this.storeRepository = storeRepository;
        this.customerRepository = customerRepository;
        this.visitLogRepository = visitLogRepository;
        this.rewardGrantRepository = rewardGrantRepository;
        this.qrTokenService = qrTokenService;
    }

    /** 온라인 진단 실행 (네이버 검색 API 4회 호출) */
    public DiagnosisResponse runDiagnosis(DiagnosisRequest req) {
        return diagnosisService.run(currentStore(), req);
    }

    /** 가장 최근 진단 결과 */
    public Optional<DiagnosisResponse> latestDiagnosis() {
        return diagnosisService.latest(currentStore());
    }

    /** 최근 N주 방문 추이 */
    public List<WeekPoint> weeklySeries(int weeks) {
        return statsService.weeklySeries(currentStore(), weeks);
    }

    /** 주간 리포트 (weekStart 없으면 이번 주) */
    public WeeklyReport weeklyReport(LocalDate weekStart) {
        return statsService.weeklyReport(currentStore(), weekStart);
    }

    /** 태블릿 화면용 갱신 QR */
    @Transactional
    public QrResponse rotatingQr() {
        Store store = currentStore();
        QrToken t = qrTokenService.currentRotating(store);
        return new QrResponse(store.getName(), t.getToken(), t.getKind().name(), t.getExpiresAt());
    }

    /** 인쇄용 장기 QR */
    @Transactional
    public QrResponse printQr() {
        Store store = currentStore();
        QrToken t = qrTokenService.printToken(store);
        return new QrResponse(store.getName(), t.getToken(), t.getKind().name(), t.getExpiresAt());
    }

    /** 로그인한 사장님의 매장 (JWT → ownerId → store) */
    private Store currentStore() {
        return storeRepository.findFirstByOwner_Id(kr.ac.ajou.shima.auth.CurrentOwner.id())
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
    }

    /** 이번주 신규/재방문 요약 + 전주 대비 증감 */
    @Transactional(readOnly = true)
    public StatsSummary summary() {
        Store store = currentStore();
        Instant thisWeekStart = weekStart(0);
        Instant nextWeekStart = weekStart(-1);   // 이번주 끝 = 다음주 시작
        Instant lastWeekStart = weekStart(1);

        List<VisitLog> thisWeek = visitLogRepository.findByStoreAndVisitedAtBetween(store, thisWeekStart, nextWeekStart);
        List<VisitLog> lastWeek = visitLogRepository.findByStoreAndVisitedAtBetween(store, lastWeekStart, thisWeekStart);

        Set<Long> newCust = thisWeek.stream().filter(VisitLog::isNewVisit)
                .map(v -> v.getCustomer().getId()).collect(Collectors.toSet());
        Set<Long> allThisWeek = thisWeek.stream().map(v -> v.getCustomer().getId()).collect(Collectors.toSet());
        Set<Long> revisit = new HashSet<>(allThisWeek);
        revisit.removeAll(newCust);

        int lastWeekVisitors = (int) lastWeek.stream().map(v -> v.getCustomer().getId()).distinct().count();

        return new StatsSummary(
                newCust.size(),
                revisit.size(),
                allThisWeek.size(),
                lastWeekVisitors,
                allThisWeek.size() - lastWeekVisitors
        );
    }

    /** 오늘 방문한 손님 (재량 서비스 부여 대상) */
    @Transactional(readOnly = true)
    public List<CustomerSummary> todayVisitors() {
        Store store = currentStore();
        Instant startOfToday = LocalDate.now(KST).atStartOfDay(KST).toInstant();
        // 오늘 방문 중 손님별 1건만 (최신순 → 먼저 나온 게 최신)
        Map<Long, VisitLog> byCustomer = new LinkedHashMap<>();
        for (VisitLog v : visitLogRepository.findByStoreAndVisitedAtGreaterThanEqualOrderByVisitedAtDesc(store, startOfToday)) {
            byCustomer.putIfAbsent(v.getCustomer().getId(), v);
        }
        if (byCustomer.isEmpty()) return List.of();
        Map<Long, VisitLogRepository.CustomerVisitStat> stats = visitStats(store);
        return byCustomer.values().stream().map(v -> toSummary(v.getCustomer(), stats)).toList();
    }

    /** 전체 단골 목록 — 마지막 방문 최신순 */
    @Transactional(readOnly = true)
    public List<CustomerSummary> customers() {
        Store store = currentStore();
        Map<Long, VisitLogRepository.CustomerVisitStat> stats = visitStats(store);
        return customerRepository.findByStore(store).stream()
                .map(c -> toSummary(c, stats))
                .sorted(Comparator.comparing(CustomerSummary::lastVisitAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 재량 서비스 부여 (MANUAL) */
    @Transactional
    public void grant(Long customerId, GrantRequest req) {
        Store store = currentStore();
        Customer customer = customerRepository.findById(customerId)
                .filter(c -> c.getStore().getId().equals(store.getId()))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 손님이 아닙니다."));
        rewardGrantRepository.save(new RewardGrant(store, customer, null, GrantType.MANUAL, req.memo()));
    }

    /** 손님 데이터 삭제 (손님이 매장에서 삭제를 요청했을 때) */
    @Transactional
    public void deleteCustomer(Long customerId) {
        Store store = currentStore();
        Customer customer = customerRepository.findById(customerId)
                .filter(c -> c.getStore().getId().equals(store.getId()))
                .orElseThrow(() -> new IllegalArgumentException("해당 매장의 손님이 아닙니다."));
        customerDataService.delete(customer, "owner");
    }

    private Map<Long, VisitLogRepository.CustomerVisitStat> visitStats(Store store) {
        Map<Long, VisitLogRepository.CustomerVisitStat> m = new HashMap<>();
        for (VisitLogRepository.CustomerVisitStat st : visitLogRepository.visitStatsByStore(store)) {
            m.put(st.getCustomerId(), st);
        }
        return m;
    }

    private CustomerSummary toSummary(Customer c, Map<Long, VisitLogRepository.CustomerVisitStat> stats) {
        VisitLogRepository.CustomerVisitStat st = stats.get(c.getId());
        int count = st == null ? 0 : st.getVisitCount().intValue();
        Instant last = st == null ? null : st.getLastVisitAt();
        return new CustomerSummary(c.getId(), c.getPhoneLast4(), c.getName(), count, c.getFirstVisitAt(), last);
    }

    /** weeksAgo주 전의 주 시작(월요일 00:00 KST). weeksAgo=-1이면 다음주 시작. */
    private Instant weekStart(int weeksAgo) {
        LocalDate monday = LocalDate.now(KST)
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .minusWeeks(weeksAgo);
        return monday.atStartOfDay(KST).toInstant();
    }
}
