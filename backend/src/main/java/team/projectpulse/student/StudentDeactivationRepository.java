package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StudentDeactivationRepository extends JpaRepository<StudentDeactivation, Long> {
    List<StudentDeactivation> findByStudentIdOrderByDeactivatedAtAsc(Integer studentId);
}
