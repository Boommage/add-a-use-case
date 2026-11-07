package team.projectpulse.student;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.projectpulse.section.Section;
import team.projectpulse.team.Team;
import java.time.LocalDateTime;

/** A half-open interval of evidenced participation; never inferred from current membership. */
@Entity
public class StudentTeamMembership {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "student_id")
    private Student student;
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "team_id")
    private Team team;
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "section_id")
    private Section section;
    @Column(nullable = false)
    private LocalDateTime joinedAt;
    private LocalDateTime leftAt;

    protected StudentTeamMembership() { }

    public StudentTeamMembership(Student student, Team team, Section section, LocalDateTime joinedAt) {
        this.student = student;
        this.team = team;
        this.section = section;
        this.joinedAt = joinedAt;
    }

    public void close(LocalDateTime leftAt) {
        if (this.leftAt != null || leftAt.isBefore(this.joinedAt)) {
            throw new IllegalStateException("Invalid membership interval close.");
        }
        this.leftAt = leftAt;
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Team getTeam() { return team; }
    public Section getSection() { return section; }
    public LocalDateTime getJoinedAt() { return joinedAt; }
    public LocalDateTime getLeftAt() { return leftAt; }
}
