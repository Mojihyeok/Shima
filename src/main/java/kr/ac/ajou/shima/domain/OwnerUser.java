package kr.ac.ajou.shima.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "owner_user",
       uniqueConstraints = @UniqueConstraint(columnNames = "login_id"))
public class OwnerUser {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String loginId;

    @Column(nullable = false)
    private String pwHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UiMode uiMode = UiMode.STANDARD;

    protected OwnerUser() {}

    public OwnerUser(String loginId, String pwHash, UiMode uiMode) {
        this.loginId = loginId;
        this.pwHash = pwHash;
        this.uiMode = uiMode;
    }

    public Long getId() { return id; }
    public String getLoginId() { return loginId; }
    public String getPwHash() { return pwHash; }
    public UiMode getUiMode() { return uiMode; }
    public void setUiMode(UiMode uiMode) { this.uiMode = uiMode; }
}
