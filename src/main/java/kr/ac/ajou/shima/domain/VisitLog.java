package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 방문 로그 — 모든 지표의 원본. 재방문율/주기/코호트가 전부 여기서 파생된다.
 * 인덱스: (store_id, visited_at), (customer_id, visited_at) — 집계/주기 계산용.
 */
@Entity
@Table(name = "visit_log",
       indexes = {
           @Index(name = "idx_visit_store_time", columnList = "store_id, visited_at"),
           @Index(name = "idx_visit_customer_time", columnList = "customer_id, visited_at")
       })
public class VisitLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "visited_at", nullable = false)
    private Instant visitedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VisitSource source;

    /** 적립 시점에 확정 — 해당 손님의 첫 방문이면 true (나중에 재계산 불필요) */
    @Column(name = "is_new_visit", nullable = false)
    private boolean newVisit;

    /** 적립에 사용된 QR 토큰 id (감사/부정적립 추적용, nullable) */
    @Column(name = "token_id")
    private Long tokenId;

    /** QR 화면이 열린 뒤 적립 완료까지 걸린 시간(ms) — PoC 목표 10초 이내 측정용, 손님 브라우저가 보낸 값 */
    @Column(name = "stamp_ms")
    private Integer stampMs;

    protected VisitLog() {}

    public VisitLog(Store store, Customer customer, Instant visitedAt,
                    VisitSource source, boolean newVisit, Long tokenId) {
        this.store = store;
        this.customer = customer;
        this.visitedAt = visitedAt;
        this.source = source;
        this.newVisit = newVisit;
        this.tokenId = tokenId;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public Customer getCustomer() { return customer; }
    public Instant getVisitedAt() { return visitedAt; }
    public VisitSource getSource() { return source; }
    public boolean isNewVisit() { return newVisit; }
    public Long getTokenId() { return tokenId; }
    public Integer getStampMs() { return stampMs; }
    public void setStampMs(Integer stampMs) { this.stampMs = stampMs; }
}
