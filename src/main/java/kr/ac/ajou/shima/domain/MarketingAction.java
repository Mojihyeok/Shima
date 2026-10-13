package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** 마케팅 액션 로그 — F3 과제 수행 시 자동 기록, F5 타임라인 오버레이의 소스 */
@Entity
@Table(name = "marketing_action",
       indexes = @Index(name = "idx_action_store_time", columnList = "store_id, acted_at"))
public class MarketingAction {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "action_type", nullable = false)
    private String actionType;   // 예: "instagram_post", "sms_blast"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActionSource source;

    @Column(name = "acted_at", nullable = false)
    private Instant actedAt;

    private String memo;

    protected MarketingAction() {}

    public MarketingAction(Store store, String actionType, ActionSource source, Instant actedAt, String memo) {
        this.store = store;
        this.actionType = actionType;
        this.source = source;
        this.actedAt = actedAt;
        this.memo = memo;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public String getActionType() { return actionType; }
    public ActionSource getSource() { return source; }
    public Instant getActedAt() { return actedAt; }
    public String getMemo() { return memo; }
}
