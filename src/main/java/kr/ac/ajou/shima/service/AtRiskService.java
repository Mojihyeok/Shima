package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.Customer;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.VisitLog;
import kr.ac.ajou.shima.dto.AtRiskCustomer;
import kr.ac.ajou.shima.repository.CustomerRepository;
import kr.ac.ajou.shima.repository.VisitLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 이탈 위험 단골 — 평소 방문 주기보다 한참 안 오는 손님 (F3 안부 문자 대상).
 *
 * 판정: 방문 2회 이상 && 마지막 방문 후 경과일 ≥ 평소 주기 × OVERDUE_FACTOR && 경과일 ≥ MIN_DAYS
 *  - 평소 주기 = (마지막 방문 - 첫 방문) ÷ (방문 수 - 1)  [연속 간격의 평균]
 *  - 같은 날 여러 번은 하루 1회 적립이라 생기지 않음
 *  - 180일 넘게 안 온 손님은 "이미 떠난 손님"으로 보고 제외 (안부 문자 대상으로 부적절)
 */
@Service
public class AtRiskService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final double OVERDUE_FACTOR = 2.0;
    static final int MIN_DAYS = 14;
    static final int MAX_DAYS = 180;
    private static final double MIN_CYCLE_DAYS = 1.0; // 주기가 너무 짧게 계산되는 경우 하한

    private final VisitLogRepository visitRepo;
    private final CustomerRepository customerRepo;

    public AtRiskService(VisitLogRepository visitRepo, CustomerRepository customerRepo) {
        this.visitRepo = visitRepo;
        this.customerRepo = customerRepo;
    }

    @Transactional(readOnly = true)
    public List<AtRiskCustomer> list(Store store, int limit) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(KST);

        // 손님별 첫/마지막 방문, 방문 수 — 방문 로그 한 번 조회
        Map<Long, long[]> agg = new HashMap<>(); // [count, firstEpochSec, lastEpochSec]
        for (VisitLog v : visitRepo.findByStoreAndVisitedAtBefore(store, now.plusSeconds(1))) {
            long t = v.getVisitedAt().getEpochSecond();
            agg.merge(v.getCustomer().getId(), new long[]{1, t, t},
                    (a, b) -> new long[]{a[0] + 1, Math.min(a[1], b[1]), Math.max(a[2], b[2])});
        }

        Map<Long, Customer> customers = customerRepo.findByStore(store).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));

        List<AtRiskCustomer> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : agg.entrySet()) {
            long[] a = e.getValue();
            int visits = (int) a[0];
            if (visits < 2) continue;
            Customer c = customers.get(e.getKey());
            if (c == null) continue;

            double cycle = Math.max(MIN_CYCLE_DAYS, (a[2] - a[1]) / 86400.0 / (visits - 1));
            Instant last = Instant.ofEpochSecond(a[2]);
            int since = (int) ChronoUnit.DAYS.between(last.atZone(KST).toLocalDate(), today);
            if (since < MIN_DAYS || since > MAX_DAYS || since < cycle * OVERDUE_FACTOR) continue;

            out.add(new AtRiskCustomer(c.getId(), c.getPhoneLast4(), c.getName(), visits,
                    Math.round(cycle * 10) / 10.0, since, Math.round(since / cycle * 10) / 10.0, last));
        }
        out.sort(Comparator.comparingDouble(AtRiskCustomer::overdueRatio).reversed()
                .thenComparing(Comparator.comparingInt(AtRiskCustomer::visitCount).reversed()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }
}
