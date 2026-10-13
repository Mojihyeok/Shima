package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * 지급 이력. MANUAL = 사장님 재량 서비스.
 * "서비스 준 손님 vs 안 준 손님"의 재방문율 비교 근거 데이터.
 */
@Entity
@Table(name = "reward_grant",
       indexes = @Index(name = "idx_grant_store_customer", columnList = "store_id, customer_id"))
public class RewardGrant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id")
    private RewardRule rule;   // AUTO일 때만, MANUAL이면 null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GrantType type;

    @CreationTimestamp
    @Column(name = "granted_at", updatable = false)
    private Instant grantedAt;

    private String memo;

    protected RewardGrant() {}

    public RewardGrant(Store store, Customer customer, RewardRule rule, GrantType type, String memo) {
        this.store = store;
        this.customer = customer;
        this.rule = rule;
        this.type = type;
        this.memo = memo;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public Customer getCustomer() { return customer; }
    public RewardRule getRule() { return rule; }
    public GrantType getType() { return type; }
    public Instant getGrantedAt() { return grantedAt; }
    public String getMemo() { return memo; }
}
