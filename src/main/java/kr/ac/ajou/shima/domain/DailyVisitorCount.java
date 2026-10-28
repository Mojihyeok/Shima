package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 하루 전체 손님 수 (사장님 입력, 영수증 건수 기준) — 적립 참여율의 분모.
 * QR 적립은 참여한 손님만 남기므로, 전체 손님 수는 시스템이 알 수 없어 직접 받는다.
 */
@Entity
@Table(name = "daily_visitor_count",
       uniqueConstraints = @UniqueConstraint(name = "uk_daily_count_store_day", columnNames = {"store_id", "visit_day"}))
public class DailyVisitorCount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "visit_day", nullable = false) // "day"는 H2 예약어라 피함
    private LocalDate day;

    @Column(name = "visitor_count", nullable = false)
    private int visitorCount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyVisitorCount() {}

    public DailyVisitorCount(Store store, LocalDate day, int visitorCount) {
        this.store = store;
        this.day = day;
        update(visitorCount);
    }

    public void update(int visitorCount) {
        this.visitorCount = visitorCount;
        this.updatedAt = Instant.now();
    }

    public LocalDate getDay() { return day; }
    public int getVisitorCount() { return visitorCount; }
}
