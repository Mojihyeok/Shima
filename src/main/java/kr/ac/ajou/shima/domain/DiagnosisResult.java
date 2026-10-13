package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** 온라인 진단 결과 이력 — 루프가 돌수록 지수 변화를 비교하기 위해 매 실행을 저장 */
@Entity
@Table(name = "diagnosis_result",
       indexes = @Index(name = "idx_diag_store_time", columnList = "store_id, created_at"))
public class DiagnosisResult {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "total_score", nullable = false)
    private int totalScore;

    /** 축별 점수·근거 전체 (DiagnosisResponse JSON) */
    @Column(name = "detail_json", nullable = false, length = 20000)
    private String detailJson;

    protected DiagnosisResult() {}

    public DiagnosisResult(Store store, int totalScore, String detailJson) {
        this.store = store;
        this.totalScore = totalScore;
        this.detailJson = detailJson;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public Instant getCreatedAt() { return createdAt; }
    public int getTotalScore() { return totalScore; }
    public String getDetailJson() { return detailJson; }
}
