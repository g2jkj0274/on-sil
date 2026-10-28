package kr.onsil.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class Classroom {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, unique = true, length = 12)
    private String inviteCode;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User owner;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Classroom() {}

    public Classroom(String name, String inviteCode, User owner) {
        this.name = name;
        this.inviteCode = inviteCode;
        this.owner = owner;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public User getOwner() { return owner; }
    public Instant getCreatedAt() { return createdAt; }
}
