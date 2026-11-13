package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "store")
public class Store {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String region;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    private OwnerUser owner;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    /** 사장님 고민·목표 (예: 저녁 손님 늘리기) — AI 처방·글쓰기 개인화 */
    @Column(name = "owner_goal", length = 300)
    private String ownerGoal;

    /** 우리 가게 특징 (입지, 손님층, 강점, 어려운 점) — AI 처방·글쓰기 개인화 */
    @Column(name = "store_notes", length = 1000)
    private String storeNotes;

    @Column(name = "profile_updated_at")
    private Instant profileUpdatedAt;

    protected Store() {}

    public Store(String name, String region, OwnerUser owner) {
        this.name = name;
        this.region = region;
        this.owner = owner;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public OwnerUser getOwner() { return owner; }
    public Instant getCreatedAt() { return createdAt; }
    public String getOwnerGoal() { return ownerGoal; }
    public String getStoreNotes() { return storeNotes; }
    public Instant getProfileUpdatedAt() { return profileUpdatedAt; }

    public void updateProfile(String ownerGoal, String storeNotes) {
        this.ownerGoal = ownerGoal;
        this.storeNotes = storeNotes;
        this.profileUpdatedAt = Instant.now();
    }
}
