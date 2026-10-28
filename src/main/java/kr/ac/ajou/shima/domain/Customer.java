package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 손님 (매장별 분리). 전화번호는 해시로만 저장하고 표시용 뒷 4자리만 별도 보관.
 * (store_id, phone_hash) 유니크 — 중복 적립 검사와 식별을 동시에 처리.
 */
@Entity
@Table(name = "customer",
       uniqueConstraints = @UniqueConstraint(name = "uk_customer_store_hash",
               columnNames = {"store_id", "phone_hash"}),
       indexes = @Index(name = "idx_customer_store_hash", columnList = "store_id, phone_hash"))
public class Customer {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    private Store store;

    @Column(name = "phone_hash", nullable = false)
    private String phoneHash;

    @Column(name = "phone_last4", nullable = false, length = 4)
    private String phoneLast4;

    private String name;         // 선택 입력

    private String birthday;     // 선택 입력, "MM-dd" (연도 미수집)

    @Column(name = "first_visit_at")
    private Instant firstVisitAt;

    /** 개인정보 수집·이용 동의 시각과 동의한 고지문 버전 (동의 전에는 손님 행을 만들지 않음) */
    @Column(name = "consented_at")
    private Instant consentedAt;

    @Column(name = "consent_version", length = 30)
    private String consentVersion;

    protected Customer() {}

    public Customer(Store store, String phoneHash, String phoneLast4) {
        this.store = store;
        this.phoneHash = phoneHash;
        this.phoneLast4 = phoneLast4;
    }

    public Long getId() { return id; }
    public Store getStore() { return store; }
    public String getPhoneHash() { return phoneHash; }
    public String getPhoneLast4() { return phoneLast4; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBirthday() { return birthday; }
    public void setBirthday(String birthday) { this.birthday = birthday; }
    public Instant getFirstVisitAt() { return firstVisitAt; }
    public void setFirstVisitAt(Instant firstVisitAt) { this.firstVisitAt = firstVisitAt; }
    public Instant getConsentedAt() { return consentedAt; }
    public String getConsentVersion() { return consentVersion; }
    public boolean hasConsent() { return consentedAt != null; }
    public void agree(String version, Instant at) { this.consentVersion = version; this.consentedAt = at; }
}
