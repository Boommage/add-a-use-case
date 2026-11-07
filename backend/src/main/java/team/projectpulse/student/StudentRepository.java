package team.projectpulse.student;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Integer>, JpaSpecificationExecutor<Student> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Student s where s.id = :studentId")
    Optional<Student> findForUpdate(Integer studentId);

    // Scope is part of the locked lookup, including explicit course ownership.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from Student s where s.id = :studentId and
              ((:admin = true and s.section.course.courseAdmin.id = :callerId) or
               (:instructor = true and exists
                 (select i.id from Section sec join sec.instructors i
                  where sec = s.section and i.id = :callerId)))
            """)
    Optional<Student> findForLifecycleUpdate(Integer studentId, Integer callerId, boolean admin, boolean instructor);

    @Query("""
            select count(s) > 0 from Student s where s.id = :studentId and
              ((:admin = true and s.section.course.courseAdmin.id = :callerId) or
               (:instructor = true and exists
                 (select i.id from Section sec join sec.instructors i
                  where sec = s.section and i.id = :callerId)))
            """)
    boolean existsInLifecycleScope(Integer studentId, Integer callerId, boolean admin, boolean instructor);

    // Find all students in a section
    List<Student> findBySectionSectionId(Integer sectionId);

    List<Student> findByTeamTeamId(Integer teamId);
}
