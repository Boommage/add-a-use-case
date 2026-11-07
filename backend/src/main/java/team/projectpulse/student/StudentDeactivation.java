package team.projectpulse.student;

import jakarta.persistence.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.projectpulse.section.Section;
import team.projectpulse.team.Team;
import java.time.LocalDateTime;

/** Append-only departure history, including departures without a team. */
@Entity
public class StudentDeactivation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "student_id")
    private Student student;
    @ManyToOne(optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "section_id")
    private Section section;
    @ManyToOne
    @OnDelete(action = OnDeleteAction.SET_NULL)
    @JoinColumn(name = "former_team_id")
    private Team formerTeam;
    @Column(nullable = false)
    private LocalDateTime deactivatedAt;
    @Column(nullable = false, length = 8)
    private String cutoffWeek;

    protected StudentDeactivation() { }

    public StudentDeactivation(Student student, LocalDateTime deactivatedAt, String cutoffWeek) {
        this.student = student;
        this.section = student.getSection();
        this.formerTeam = student.getTeam();
        this.deactivatedAt = deactivatedAt;
        this.cutoffWeek = cutoffWeek;
    }

    public Long getId() { return id; }
    public Student getStudent() { return student; }
    public Section getSection() { return section; }
    public Team getFormerTeam() { return formerTeam; }
    public LocalDateTime getDeactivatedAt() { return deactivatedAt; }
    public String getCutoffWeek() { return cutoffWeek; }
}
