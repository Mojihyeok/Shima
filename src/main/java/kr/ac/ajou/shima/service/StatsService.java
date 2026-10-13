package kr.ac.ajou.shima.service;

import kr.ac.ajou.shima.domain.GrantType;
import kr.ac.ajou.shima.domain.MetricSnapshot;
import kr.ac.ajou.shima.domain.Store;
import kr.ac.ajou.shima.domain.VisitLog;
import kr.ac.ajou.shima.dto.WeekPoint;
import kr.ac.ajou.shima.dto.WeeklyReport;
import kr.ac.ajou.shima.repository.MetricSnapshotRepository;
import kr.ac.ajou.shima.repository.RewardGrantRepository;
import kr.ac.ajou.shima.repository.VisitLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 방문 집계 · 주간 리포트. 모든 숫자는 visit_log 하나에서 파생된다.
 *
 * 정의 (발표·리포트 문구와 일치시킬 것)
 *  - 주: 월요일 00:00 ~ 다음 월요일 00:00 (KST)
 *  - 신규 손님: 그 주에 "첫 방문"이 있는 손님
 *  - 재방문 손님: 그 주에 방문했지만 첫 방문은 그 이전인 손님
 *  - 재방문 비율: 재방문 손님 / 그 주 방문 손님
 *  - 평균 방문 주기: 주말 시점까지 2회 이상 온 손님 각자의 평균 방문 간격을 다시 평균 (일)
 */
@Service
public class StatsService {

    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final VisitLogRepository visitRepo;
    private final RewardGrantRepository grantRepo;
    private final MetricSnapshotRepository snapshotRepo;

    public StatsService(VisitLogRepository visitRepo, RewardGrantRepository grantRepo,
                        MetricSnapshotRepository snapshotRepo) {
        this.visitRepo = visitRepo;
        this.grantRepo = grantRepo;
        this.snapshotRepo = snapshotRepo;
    }

    // ---------- 날짜 도우미 ----------
    public static LocalDate weekStartOf(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static LocalDate thisWeekStart() {
        return weekStartOf(LocalDate.now(KST));
    }

    private static Instant at(LocalDate d) {
        return d.atStartOfDay(KST).toInstant();
    }

    // ---------- 집계 기본 단위 ----------
    public record WeekMetrics(int newCount, int revisitCount, Double revisitRate, Double avgCycleDays) {
        public int visitors() { return newCount + revisitCount; }
    }

    private record Counts(int newCount, int revisitCount) {}

    private static Counts count(Collection<VisitLog> visits) {
        Set<Long> all = new HashSet<>();
        Set<Long> fresh = new HashSet<>();
        for (VisitLog v : visits) {
            Long id = v.getCustomer().getId(); // 지연로딩 프록시에서도 id는 추가 쿼리 없이 얻음
            all.add(id);
            if (v.isNewVisit()) fresh.add(id);
        }
        return new Counts(fresh.size(), all.size() - fresh.size());
    }

    // ---------- 최근 N주 추이 ----------
    @Transactional(readOnly = true)
    public List<WeekPoint> weeklySeries(Store store, int weeks) {
        int n = Math.max(1, Math.min(weeks, 26));
        LocalDate current = thisWeekStart();
        LocalDate first = current.minusWeeks(n - 1);

        Map<LocalDate, List<VisitLog>> byWeek = visitRepo
                .findByStoreAndVisitedAtBetween(store, at(first), at(current.plusWeeks(1)))
                .stream()
                .collect(Collectors.groupingBy(v -> weekStartOf(v.getVisitedAt().atZone(KST).toLocalDate())));

        List<WeekPoint> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LocalDate ws = first.plusWeeks(i);
            Counts c = count(byWeek.getOrDefault(ws, List.of()));
            out.add(new WeekPoint(ws, c.newCount(), c.revisitCount(), ws.equals(current)));
        }
        return out;
    }

    // ---------- 주간 리포트 ----------
    @Transactional
    public WeeklyReport weeklyReport(Store store, LocalDate requested) {
        LocalDate current = thisWeekStart();
        LocalDate ws = requested == null ? current : weekStartOf(requested);
        if (ws.isAfter(current)) ws = current;

        WeekMetrics m = metrics(store, ws);
        WeekMetrics prev = metrics(store, ws.minusWeeks(1));
        Double changePct = prev.visitors() == 0 ? null
                : (m.visitors() - prev.visitors()) * 100.0 / prev.visitors();

        Instant from = at(ws), to = at(ws.plusWeeks(1));
        long auto = grantRepo.countByStoreAndTypeAndGrantedAtBetween(store, GrantType.AUTO, from, to);
        long manual = grantRepo.countByStoreAndTypeAndGrantedAtBetween(store, GrantType.MANUAL, from, to);

        return new WeeklyReport(ws, ws.plusDays(6), ws.equals(current),
                m.newCount(), m.revisitCount(), m.visitors(), prev.visitors(), changePct,
                m.revisitRate(), m.avgCycleDays(), auto, manual);
    }

    /**
     * 한 주의 지표. 끝난 주는 metric_snapshot에서 읽고(없으면 계산해 저장),
     * 진행 중인 주는 매번 실시간 계산. 방문은 항상 "지금" 시각으로만 쌓이므로 끝난 주의 값은 바뀌지 않는다.
     */
    @Transactional
    public WeekMetrics metrics(Store store, LocalDate ws) {
        boolean complete = ws.isBefore(thisWeekStart());
        if (complete) {
            Optional<MetricSnapshot> snap = snapshotRepo.findByStoreAndWeekStart(store, ws);
            if (snap.isPresent()) {
                MetricSnapshot s = snap.get();
                return new WeekMetrics(s.getNewCnt(), s.getRevisitCnt(), s.getRevisitRate(), s.getAvgCycleDays());
            }
        }
        WeekMetrics m = compute(store, ws);
        if (complete) {
            snapshotRepo.save(new MetricSnapshot(store, ws, m.newCount(), m.revisitCount(),
                    m.revisitRate(), m.avgCycleDays()));
        }
        return m;
    }

    private WeekMetrics compute(Store store, LocalDate ws) {
        Instant from = at(ws), to = at(ws.plusWeeks(1));
        List<VisitLog> week = visitRepo.findByStoreAndVisitedAtBetween(store, from, to).stream()
                .filter(v -> v.getVisitedAt().isBefore(to)) // Between은 양끝 포함 → 다음 주 00:00 제외
                .toList();
        Counts c = count(week);
        int visitors = c.newCount() + c.revisitCount();
        Double rate = visitors == 0 ? null : (double) c.revisitCount() / visitors;
        return new WeekMetrics(c.newCount(), c.revisitCount(), rate, avgCycleDays(store, to));
    }

    /** until 시점까지 2회 이상 방문한 손님들의 평균 방문 간격(일)의 평균. 대상 없으면 null */
    public Double avgCycleDays(Store store, Instant until) {
        Map<Long, List<Instant>> byCustomer = new HashMap<>();
        for (VisitLog v : visitRepo.findByStoreAndVisitedAtBefore(store, until)) {
            byCustomer.computeIfAbsent(v.getCustomer().getId(), k -> new ArrayList<>()).add(v.getVisitedAt());
        }
        double sum = 0;
        int n = 0;
        for (List<Instant> times : byCustomer.values()) {
            if (times.size() < 2) continue;
            Collections.sort(times);
            double spanDays = Duration.between(times.get(0), times.get(times.size() - 1)).toMinutes() / 1440.0;
            sum += spanDays / (times.size() - 1); // 연속 간격들의 평균 = 전체 기간 / (방문수-1)
            n++;
        }
        return n == 0 ? null : sum / n;
    }
}
