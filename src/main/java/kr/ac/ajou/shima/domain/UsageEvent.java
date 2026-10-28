package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** 사장님 사용 기록 (PoC 측정용) — 리포트·진단 화면 열람 횟수 */
@Entity
@Table(name = "usage_event", indexes = @Index(name = "idx_usage_store_time", columnList = "store_id, occurred_at"))
public class UsageEvent {

    public enum Type { REPORT_VIEW, DIAGNOSIS_VIEW }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected UsageEvent() {}

    public UsageEvent(Store store, Type type) {
        this.store = store;
        this.type = type;
        this.occurredAt = Instant.now();
    }

    public Type getType() { return type; }
    public Instant getOccurredAt() { return occurredAt; }
}
