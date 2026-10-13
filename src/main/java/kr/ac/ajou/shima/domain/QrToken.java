package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/** QR 갱신 토큰 — 이미지 캡처 재사용 방지를 위해 주기적으로 발급/만료 */
@Entity
@Table(name = "qr_token",
       uniqueConstraints = @UniqueConstraint(columnNames = "token"),
       indexes = @Index(name = "idx_qr_store", columnList = "store_id"))
public class QrToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(nullable = false)
    private String token;

    @CreationTimestamp
    @Column(name = "issued_at", updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QrKind kind;

    protected QrToken() {}

    public QrToken(Store store, String token, Instant expiresAt, QrKind kind) {
        this.store = store;
        this.token = token;
        this.expiresAt = expiresAt;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public String getToken() { return token; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public QrKind getKind() { return kind; }
}
