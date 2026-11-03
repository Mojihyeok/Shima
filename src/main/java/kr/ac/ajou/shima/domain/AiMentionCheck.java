package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** AI 언급률 확인 결과 (진단 참고 지표 — 점수에는 넣지 않음). 질문별 상세는 JSON */
@Entity
@Table(name = "ai_mention_check", indexes = @Index(name = "idx_ai_check_store_time", columnList = "store_id, checked_at"))
public class AiMentionCheck {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private int asked;

    @Column(nullable = false)
    private int mentioned;

    @Column(length = 60)
    private String model;

    @Column(name = "detail_json", columnDefinition = "text", nullable = false)
    private String detailJson;

    protected AiMentionCheck() {}

    public AiMentionCheck(Store store, int asked, int mentioned, String model, String detailJson) {
        this.store = store;
        this.checkedAt = Instant.now();
        this.asked = asked;
        this.mentioned = mentioned;
        this.model = model;
        this.detailJson = detailJson;
    }

    public String getDetailJson() { return detailJson; }
}
