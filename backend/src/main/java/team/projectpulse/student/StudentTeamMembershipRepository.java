package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.List;

public interface StudentTeamMembershipRepository extends JpaRepository<StudentTeamMembership, Long> {
    List<StudentTeamMembership> findByStudentIdAndLeftAtIsNull(Integer studentId);

    @Query("""
            select m from StudentTeamMembership m
            where m.section.sectionId = :sectionId
              and m.joinedAt < :end and (m.leftAt is null or m.leftAt > :start)
            """)
    List<StudentTeamMembership> findParticipants(Integer sectionId, LocalDateTime start, LocalDateTime end);
}
