package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * AI 처방(F3) — "이번 주 할 일" 1건. 내용(이유·단계·문구 초안)은 JSON으로 보관.
 * 사장님이 "했어요"(DONE, 고른 문구 번호) 또는 "건너뛸게요"(SKIPPED)를 누르면 다음 추천에 반영.
 */
@Entity
@Table(name = "coach_task", indexes = @Index(name = "idx_coach_store_time", columnList = "store_id, created_at"))
public class CoachTask {

    public enum Status { PENDING, DONE, SKIPPED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "task_type", nullable = false, length = 40)
    private String taskType;

    @Column(nullable = false, length = 120)
    private String title;

    /** AI(Gemini) 또는 RULE(규칙 기반 대체) */
    @Column(nullable = false, length = 10)
    private String source;

    @Column(length = 60)
    private String model;

    @Column(name = "payload_json", columnDefinition = "text", nullable = false)
    private String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "chosen_draft")
    private Integer chosenDraft;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected CoachTask() {}

    public CoachTask(Store store, String taskType, String title, String source, String model, String payloadJson) {
        this.store = store;
        this.createdAt = Instant.now();
        this.taskType = taskType;
        this.title = title;
        this.source = source;
        this.model = model;
        this.payloadJson = payloadJson;
    }

    public void done(Integer chosenDraft) {
        this.status = Status.DONE;
        this.chosenDraft = chosenDraft;
        this.decidedAt = Instant.now();
    }

    public void skip() {
        this.status = Status.SKIPPED;
        this.decidedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public Instant getCreatedAt() { return createdAt; }
    public String getTaskType() { return taskType; }
    public String getTitle() { return title; }
    public String getSource() { return source; }
    public String getModel() { return model; }
    public String getPayloadJson() { return payloadJson; }
    public Status getStatus() { return status; }
    public Integer getChosenDraft() { return chosenDraft; }
    public Instant getDecidedAt() { return decidedAt; }
}
