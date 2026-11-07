package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.system.exception.StudentAlreadyDeactivatedException;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student: service transaction and real persistence, before endpoint wiring. */
class StudentLifecyclePersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired StudentService service;
    @MockitoSpyBean StudentRepository students;
    @Autowired StudentTeamMembershipRepository memberships;
    @Autowired StudentDeactivationRepository departures;
    @Autowired PeerEvaluationRepository evaluations;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    Student student;

    @BeforeEach
    void selectStudentAndAuthorizeOwningAdmin() {
        student = students.findAll().stream().filter(s -> s.getEmail().equals("j.smith@abc.edu")).findFirst().orElseThrow();
        authorize(student.getSection().getCourse().getCourseAdmin().getId(), "ROLE_admin", "ROLE_instructor");
    }

    void authorize(Integer id, String... roles) {
        Jwt jwt = Jwt.withTokenValue("service-test").header("alg", "RS256")
                .claim("userId", id).subject("service-test").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void owningAdminDeactivatesWithoutDeletingWorkOrCourseSection() {
        Integer id = student.getId();
        Integer sectionId = student.getSection().getSectionId();
        Integer formerTeamId = student.getTeam().getTeamId();
        long evaluationsBefore = evaluations.count();
        service.deactivateStudent(id);
        entityManager.flush();
        entityManager.clear();
        Student stored = students.findById(id).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getTeam()).isNull();
        assertThat(stored.getSection().getSectionId()).isEqualTo(sectionId);
        assertThat(evaluations.count()).isEqualTo(evaluationsBefore);
        assertThat(memberships.findByStudentIdAndLeftAtIsNull(id)).isEmpty();
        assertThat(departures.findByStudentIdOrderByDeactivatedAtAsc(id)).singleElement()
                .satisfies(d -> assertThat(d.getFormerTeam().getTeamId()).isEqualTo(formerTeamId));
    }

    @Test
    void assignedInstructorCanDeactivate() {
        Integer instructorId = student.getSection().getInstructors().iterator().next().getId();
        authorize(instructorId, "ROLE_instructor");
        assertThat(service.deactivateStudent(student.getId()).isEnabled()).isFalse();
    }

    @Test
    void noTeamDepartureIsStillRecorded() {
        student.getTeam().removeStudent(student);
        entityManager.flush();
        service.deactivateStudent(student.getId());
        entityManager.flush();
        assertThat(departures.findByStudentIdOrderByDeactivatedAtAsc(student.getId())).singleElement()
                .satisfies(d -> assertThat(d.getFormerTeam()).isNull());
    }

    @Test
    void unrelatedAdminAndInstructorAndSelfCannotMutateStudent() {
        Integer id = student.getId();
        Integer teamId = student.getTeam().getTeamId();
        for (String role : new String[]{"ROLE_admin", "ROLE_instructor", "ROLE_student"}) {
            authorize(role.equals("ROLE_student") ? id : Integer.MAX_VALUE, role);
            assertThatThrownBy(() -> service.deactivateStudent(id)).isInstanceOf(AccessDeniedException.class);
        }
        assertThat(student.isEnabled()).isTrue();
        assertThat(student.getTeam().getTeamId()).isEqualTo(teamId);
        assertThat(departures.findByStudentIdOrderByDeactivatedAtAsc(id)).isEmpty();
    }

    @Test
    void secondDeactivationCannotAppendAnotherDeparture() {
        service.deactivateStudent(student.getId());
        assertThatThrownBy(() -> service.deactivateStudent(student.getId())).isInstanceOf(StudentAlreadyDeactivatedException.class);
        assertThat(departures.findByStudentIdOrderByDeactivatedAtAsc(student.getId())).hasSize(1);
    }

    @Test
    void persistenceFailureRollsBackAccountTeamAndHistoryInAnIndependentTransaction() {
        Integer id = student.getId();
        Integer teamId = student.getTeam().getTeamId();
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        long before = tx.execute(status -> departures.count());
        doThrow(new IllegalStateException("Simulated student persistence failure")).when(students).save(any(Student.class));
        try {
            assertThatThrownBy(() -> tx.execute(status -> service.deactivateStudent(id)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("Simulated student persistence failure");
        } finally {
            reset(students);
        }
        tx.executeWithoutResult(status -> {
            Student stored = students.findById(id).orElseThrow();
            assertThat(stored.isEnabled()).isTrue();
            assertThat(stored.getTeam().getTeamId()).isEqualTo(teamId);
            assertThat(departures.count()).isEqualTo(before);
            assertThat(memberships.findByStudentIdAndLeftAtIsNull(id)).isNotEmpty();
        });
    }
}
