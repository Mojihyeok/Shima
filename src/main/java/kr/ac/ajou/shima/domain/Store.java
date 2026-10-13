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
}
