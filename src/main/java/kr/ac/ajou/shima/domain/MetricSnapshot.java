package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * 주간 지표 스냅샷 — visit_log를 주 1회 집계해 저장. 리포트는 이 스냅샷을 읽어 빠르게 렌더.
 * (store_id, week_start) 유니크.
 */
@Entity
@Table(name = "metric_snapshot",
       uniqueConstraints = @UniqueConstraint(name = "uk_snapshot_store_week",
               columnNames = {"store_id", "week_start"}))
public class MetricSnapshot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(name = "new_cnt", nullable = false)
    private int newCnt;

    @Column(name = "revisit_cnt", nullable = false)
    private int revisitCnt;

    @Column(name = "revisit_rate")
    private Double revisitRate;      // 0.0 ~ 1.0

    @Column(name = "avg_cycle_days")
    private Double avgCycleDays;     // 방문 2회 미만이면 null

    protected MetricSnapshot() {}

    public MetricSnapshot(Store store, LocalDate weekStart, int newCnt, int revisitCnt,
                          Double revisitRate, Double avgCycleDays) {
        this.store = store;
        this.weekStart = weekStart;
        this.newCnt = newCnt;
        this.revisitCnt = revisitCnt;
        this.revisitRate = revisitRate;
        this.avgCycleDays = avgCycleDays;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public LocalDate getWeekStart() { return weekStart; }
    public int getNewCnt() { return newCnt; }
    public int getRevisitCnt() { return revisitCnt; }
    public Double getRevisitRate() { return revisitRate; }
    public Double getAvgCycleDays() { return avgCycleDays; }
}
