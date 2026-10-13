package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 채택/거부 피드백 (P1) — 사장님이 3안 중 무엇을 골랐는지, 어떤 과제를 건너뛰었는지 기록 */
@Entity
@Table(name = "feedback_log",
       indexes = @Index(name = "idx_feedback_store", columnList = "store_id"))
public class FeedbackLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "task_id")
    private String taskId;

    @Column(name = "chosen_option")
    private String chosenOption;

    @Column(nullable = false)
    private boolean skipped;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    protected FeedbackLog() {}

    public FeedbackLog(Store store, String taskId, String chosenOption, boolean skipped) {
        this.store = store;
        this.taskId = taskId;
        this.chosenOption = chosenOption;
        this.skipped = skipped;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public String getTaskId() { return taskId; }
    public String getChosenOption() { return chosenOption; }
    public boolean isSkipped() { return skipped; }
    public Instant getCreatedAt() { return createdAt; }
}
