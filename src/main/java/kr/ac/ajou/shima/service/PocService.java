package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.auth.CurrentOwner;
import kr.ac.ajou.shima.domain.*;
import kr.ac.ajou.shima.dto.DailyCountRequest;
import kr.ac.ajou.shima.dto.DailyCountResponse;
import kr.ac.ajou.shima.dto.PocMetrics;
import kr.ac.ajou.shima.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * PoC 측정 — 하루 손님 수 입력, 사장님 사용 기록, 성공 지표 집계.
 * 지표 정의는 PocMetrics 주석 참고. 기간은 한국 날짜 기준 [from, to] (양끝 포함).
 */
@Service
public class PocService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StoreRepository storeRepository;
    private final DailyVisitorCountRepository dailyRepo;
    private final UsageEventRepository usageRepo;
    private final VisitLogRepository visitRepo;
    private final CustomerRepository customerRepo;
    private final RewardGrantRepository grantRepo;

    public PocService(StoreRepository storeRepository, DailyVisitorCountRepository dailyRepo,
                      UsageEventRepository usageRepo, VisitLogRepository visitRepo,
                      CustomerRepository customerRepo, RewardGrantRepository grantRepo) {
        this.storeRepository = storeRepository;
        this.dailyRepo = dailyRepo;
        this.usageRepo = usageRepo;
        this.visitRepo = visitRepo;
        this.customerRepo = customerRepo;
        this.grantRepo = grantRepo;
    }

    private Store currentStore() {
        return storeRepository.findFirstByOwner_Id(CurrentOwner.id())
                .orElseThrow(() -> new IllegalStateException("연결된 매장이 없습니다."));
    }

    private static Instant at(LocalDate d) { return d.atStartOfDay(KST).toInstant(); }

    // ---------- 하루 전체 손님 수 ----------

    @Transactional(readOnly = true)
    public DailyCountResponse getDailyCount(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(KST) : date;
        Integer count = dailyRepo.findByStoreAndDay(currentStore(), day)
                .map(DailyVisitorCount::getVisitorCount).orElse(null);
        return new DailyCountResponse(day, count);
    }

    @Transactional
    public DailyCountResponse setDailyCount(DailyCountRequest req) {
        LocalDate today = LocalDate.now(KST);
        LocalDate day = req.date() == null ? today : req.date();
        if (day.isAfter(today)) throw new IllegalArgumentException("미래 날짜는 입력할 수 없어요.");
        if (day.isBefore(today.minusDays(30))) throw new IllegalArgumentException("30일 이내 날짜만 입력할 수 있어요.");
        Store store = currentStore();
        DailyVisitorCount row = dailyRepo.findByStoreAndDay(store, day)
                .orElseGet(() -> dailyRepo.save(new DailyVisitorCount(store, day, req.count())));
        row.update(req.count());
        return new DailyCountResponse(day, row.getVisitorCount());
    }

    // ---------- 사장님 사용 기록 ----------

    @Transactional
    public void recordEvent(String type) {
        UsageEvent.Type t;
        try {
            t = UsageEvent.Type.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("알 수 없는 기록 종류예요.");
        }
        usageRepo.save(new UsageEvent(currentStore(), t));
    }

    // ---------- PoC 지표 ----------

    @Transactional(readOnly = true)
    public PocMetrics metrics(LocalDate fromReq, LocalDate toReq) {
        LocalDate today = LocalDate.now(KST);
        LocalDate to = toReq == null ? today : toReq;
        LocalDate from = fromReq == null ? to.minusDays(13) : fromReq; // 기본: 최근 14일
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 늦어요.");
        if (from.isBefore(to.minusDays(366))) throw new IllegalArgumentException("기간은 1년 이내로 해 주세요.");

        Store store = currentStore();
        Instant start = at(from), end = at(to.plusDays(1)); // [start, end)

        // 재방문 판단을 위해 기간 끝 + 14일까지 방문을 한 번에 가져옴
        List<VisitLog> visits = visitRepo.findByStoreAndVisitedAtBetween(store, start, end.plus(Duration.ofDays(14)));
        List<VisitLog> inRange = visits.stream().filter(v -> v.getVisitedAt().isBefore(end)).toList();

        // 1) 적립 참여율
        Map<LocalDate, Integer> counted = new HashMap<>();
        for (DailyVisitorCount d : dailyRepo.findByStoreAndDayBetween(store, from, to)) {
            counted.put(d.getDay(), d.getVisitorCount());
        }
        int entered = counted.values().stream().mapToInt(Integer::intValue).sum();
        int qrOnCounted = (int) inRange.stream()
                .filter(v -> counted.containsKey(v.getVisitedAt().atZone(KST).toLocalDate())).count();
        Double participation = entered == 0 ? null : (double) qrOnCounted / entered;

        // 2) 적립 소요 시간
        List<Integer> ms = inRange.stream().map(VisitLog::getStampMs).filter(Objects::nonNull).sorted().toList();
        Double median = ms.isEmpty() ? null : percentile(ms, 0.5) / 1000.0;
        Double p90 = ms.isEmpty() ? null : percentile(ms, 0.9) / 1000.0;
        Double within10 = ms.isEmpty() ? null : ms.stream().filter(x -> x <= 10_000).count() / (double) ms.size();

        // 3) 첫 방문 후 14일 안에 재방문
        Map<Long, List<Instant>> byCustomer = new HashMap<>();
        for (VisitLog v : visits) {
            byCustomer.computeIfAbsent(v.getCustomer().getId(), k -> new ArrayList<>()).add(v.getVisitedAt());
        }
        Set<Long> newIds = new HashSet<>();
        for (VisitLog v : inRange) if (v.isNewVisit()) newIds.add(v.getCustomer().getId());
        int returned = 0;
        for (Long id : newIds) {
            List<Instant> times = byCustomer.get(id);
            Collections.sort(times);
            Instant first = times.get(0);
            Instant limit = first.plus(Duration.ofDays(14));
            if (times.stream().anyMatch(t -> t.isAfter(first) && !t.isAfter(limit))) returned++;
        }

        // 4) 사장님 사용
        long reportViews = usageRepo.countByStoreAndTypeAndOccurredAtBetween(store, UsageEvent.Type.REPORT_VIEW, start, end);
        long diagnosisViews = usageRepo.countByStoreAndTypeAndOccurredAtBetween(store, UsageEvent.Type.DIAGNOSIS_VIEW, start, end);
        long manual = grantRepo.countByStoreAndTypeAndGrantedAtBetween(store, GrantType.MANUAL, start, end);
        long auto = grantRepo.countByStoreAndTypeAndGrantedAtBetween(store, GrantType.AUTO, start, end);

        // 5) 유입 경로
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (String k : List.of("instagram", "blog", "friend", "pass", "none")) sources.put(k, 0);
        for (Object[] row : customerRepo.countSourcesByFirstVisit(store, start, end)) {
            String key = row[0] == null ? "none" : (String) row[0];
            sources.merge(key, ((Number) row[1]).intValue(), Integer::sum);
        }

        return new PocMetrics(from, to,
                inRange.size(), counted.size(), entered, qrOnCounted, participation,
                ms.size(), median, p90, within10,
                newIds.size(), returned,
                reportViews, diagnosisViews, manual, auto,
                sources);
    }

    /** 정렬된 목록의 백분위 (가까운 순위 방식) */
    private static double percentile(List<Integer> sorted, double p) {
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
