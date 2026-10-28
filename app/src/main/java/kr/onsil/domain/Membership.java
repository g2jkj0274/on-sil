package kr.onsil.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"classroom_id", "user_id"}))
public class Membership {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private Classroom classroom;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClassRole role;

    @Column(nullable = false)
    private Instant joinedAt = Instant.now();

    protected Membership() {}

    public Membership(Classroom classroom, User user, ClassRole role) {
        this.classroom = classroom;
        this.user = user;
        this.role = role;
    }

    public Long getId() { return id; }
    public Classroom getClassroom() { return classroom; }
    public User getUser() { return user; }
    public ClassRole getRole() { return role; }
    public void setRole(ClassRole role) { this.role = role; }
    public Instant getJoinedAt() { return joinedAt; }
}
