package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

/** 리워드 규칙 — "N회 방문 시 OO 제공" */
@Entity
@Table(name = "reward_rule")
public class RewardRule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "stamp_goal", nullable = false)
    private int stampGoal;

    @Column(name = "reward_name", nullable = false)
    private String rewardName;

    @Column(nullable = false)
    private boolean active = true;

    protected RewardRule() {}

    public RewardRule(Store store, int stampGoal, String rewardName) {
        this.store = store;
        this.stampGoal = stampGoal;
        this.rewardName = rewardName;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public int getStampGoal() { return stampGoal; }
    public void setStampGoal(int stampGoal) { this.stampGoal = stampGoal; }
    public String getRewardName() { return rewardName; }
    public void setRewardName(String rewardName) { this.rewardName = rewardName; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
