package team.projectpulse.student;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.course.Course;
import team.projectpulse.course.CourseRepository;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.exception.StudentAlreadyDeactivatedException;
import team.projectpulse.team.Team;
import team.projectpulse.team.TeamRepository;
import team.projectpulse.team.TeamService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Service-level UC-STU-deactivate-student tests. JWTs supply caller identity, not authentication verification. */
class StudentLifecyclePersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired StudentService service;
    @Autowired StudentSecurityService security;
    @Autowired StudentRepository students;
    @Autowired StudentTeamMembershipRepository memberships;
    @Autowired StudentDeactivationRepository departures;
    @Autowired InstructorRepository instructors;
    @Autowired CourseRepository courses;
    @Autowired SectionRepository sections;
    @Autowired TeamRepository teams;
    @Autowired TeamService teamService;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
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
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    record Work(List<Map<String, Object>> activities, List<Map<String, Object>> evaluations,
                List<Map<String, Object>> ratings) { }

    Work work(Integer id) {
        return new Work(jdbc.queryForList("select * from activity where student_id = ? order by activity_id", id),
                jdbc.queryForList("select * from peer_evaluation where evaluator_id = ? or evaluatee_id = ? order by peer_evaluation_id", id, id),
                jdbc.queryForList("select r.* from rating r join peer_evaluation p on r.peer_evaluation_peer_evaluation_id = p.peer_evaluation_id where p.evaluator_id = ? or p.evaluatee_id = ? order by r.rating_id", id, id));
    }

    Map<String, Object> account(Integer id) {
        return jdbc.queryForMap("select id, username, first_name, last_name, email, password, roles, section_section_id from peer_evaluation_user where id = ?", id);
    }

    List<Map<String, Object>> history(String table, Integer id) {
        return jdbc.queryForList("select * from " + table + " where student_id = ? order by id", id);
    }

    void assertPersistedDeactivation(Integer id, Integer oldTeamId, Map<String, Object> oldAccount, Work oldWork) {
        entityManager.flush();
        entityManager.clear();
        Student stored = students.findById(id).orElseThrow();
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.getTeam()).isNull();
        assertThat(account(id)).isEqualTo(oldAccount);
        assertThat(work(id)).isEqualTo(oldWork);
        assertThat(memberships.findByStudentIdAndLeftAtIsNull(id)).isEmpty();
        assertThat(departures.findByStudentIdOrderByDeactivatedAtAsc(id)).singleElement().satisfies(d -> {
            assertThat(d.getStudent().getId()).isEqualTo(id);
            assertThat(d.getSection().getSectionId()).isEqualTo(oldAccount.get("section_section_id"));
            assertThat(d.getFormerTeam() == null ? null : d.getFormerTeam().getTeamId()).isEqualTo(oldTeamId);
        });
    }

    @Test
    void owningAdminDeactivatesWithoutDeletingWorkOrCourseSection() {
        Integer id = student.getId();
        Integer formerTeamId = student.getTeam().getTeamId();
        Integer ownerId = student.getSection().getCourse().getCourseAdmin().getId();
        // Course ownership must work independently of instructor assignment.
        student.getSection().getInstructors().removeIf(i -> i.getId().equals(ownerId));
        entityManager.flush();
        authorize(ownerId, "ROLE_admin");
        assertThat(security.mayManageLifecycle(id)).isTrue();
        var oldAccount = account(id);
        Work oldWork = work(id);
        assertThat(oldWork.activities()).isNotEmpty();
        assertThat(oldWork.evaluations()).isNotEmpty();
        assertThat(oldWork.ratings()).isNotEmpty();
        var open = memberships.findByStudentIdAndLeftAtIsNull(id);
        assertThat(open).hasSize(1);
        Long intervalId = open.getFirst().getId();
        LocalDateTime joinedAt = open.getFirst().getJoinedAt();
        service.deactivateStudent(id);
        assertPersistedDeactivation(id, formerTeamId, oldAccount, oldWork);
        var closed = memberships.findById(intervalId).orElseThrow();
        assertThat(closed.getJoinedAt()).isEqualTo(joinedAt);
        assertThat(closed.getLeftAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        assertThat(closed.getTeam().getTeamId()).isEqualTo(formerTeamId);
    }

    @Test
    void assignedInstructorCanDeactivate() {
        Integer ownerId = student.getSection().getCourse().getCourseAdmin().getId();
        Integer instructorId = student.getSection().getInstructors().stream()
                .map(Instructor::getId).filter(id -> !id.equals(ownerId)).findFirst().orElseThrow();
        authorize(instructorId, "ROLE_instructor");
        assertThat(security.mayManageLifecycle(student.getId())).isTrue();
        var oldAccount = account(student.getId());
        Work oldWork = work(student.getId());
        Integer formerTeamId = student.getTeam().getTeamId();
        service.deactivateStudent(student.getId());
        assertPersistedDeactivation(student.getId(), formerTeamId, oldAccount, oldWork);
    }

    @Test
    void noTeamDepartureIsStillRecorded() {
        teamService.removeStudentFromTeam(student.getTeam().getTeamId(), student.getId());
        entityManager.flush();
        var oldAccount = account(student.getId());
        Work oldWork = work(student.getId());
        assertThat(oldWork.activities()).isNotEmpty();
        assertThat(oldWork.evaluations()).isNotEmpty();
        service.deactivateStudent(student.getId());
        assertPersistedDeactivation(student.getId(), null, oldAccount, oldWork);
    }

    Instructor actor(String name, String roles) {
        return instructors.saveAndFlush(new Instructor(name, name, "Audit", name + "@audit.example", "hashed", true, roles));
    }

    void assertDeniedWithoutChanges(Integer actorId, String... roles) {
        Integer id = student.getId();
        authorize(actorId, roles);
        entityManager.flush();
        var beforeAccount = jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id);
        var beforeMemberships = history("student_team_membership", id);
        var beforeDepartures = history("student_deactivation", id);
        Work beforeWork = work(id);
        assertThat(security.mayManageLifecycle(id)).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(id)).isInstanceOf(AccessDeniedException.class);
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id)).isEqualTo(beforeAccount);
        assertThat(history("student_team_membership", id)).isEqualTo(beforeMemberships);
        assertThat(history("student_deactivation", id)).isEqualTo(beforeDepartures);
        assertThat(work(id)).isEqualTo(beforeWork);
    }

    @Test
    void anotherCoursesAdminCannotDeactivate() {
        Instructor otherAdmin = actor("other-admin", "admin instructor");
        Course other = new Course("Other course", "Other admin's course");
        other.setCourseAdmin(otherAdmin);
        courses.saveAndFlush(other);
        assertDeniedWithoutChanges(otherAdmin.getId(), "ROLE_admin", "ROLE_instructor");
    }

    @Test
    void sameCourseInstructorWithoutSectionAssignmentCannotDeactivate() {
        Instructor unassigned = actor("unassigned", "instructor");
        student.getSection().getCourse().addInstructor(unassigned);
        entityManager.flush();
        assertDeniedWithoutChanges(unassigned.getId(), "ROLE_instructor");
    }

    @Test
    void anotherStudentCannotDeactivate() {
        Integer otherId = students.findAll().stream().map(Student::getId)
                .filter(id -> !id.equals(student.getId())).findFirst().orElseThrow();
        assertDeniedWithoutChanges(otherId, "ROLE_student");
    }

    @Test
    void studentCannotDeactivateSelf() {
        assertDeniedWithoutChanges(student.getId(), "ROLE_student");
    }

    @Test
    void missingLifecycleTargetsFailClosed() {
        assertThat(security.mayManageLifecycle(null)).isFalse();
        assertThat(security.mayManageLifecycle(Integer.MAX_VALUE)).isFalse();
        assertThatThrownBy(() -> service.deactivateStudent(Integer.MAX_VALUE)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void secondDeactivationCannotAppendAnotherDeparture() {
        Integer id = student.getId();
        service.deactivateStudent(id);
        entityManager.flush();
        var beforeAccount = jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id);
        var beforeMemberships = history("student_team_membership", id);
        var beforeDepartures = history("student_deactivation", id);
        assertThatThrownBy(() -> service.deactivateStudent(id)).isInstanceOf(StudentAlreadyDeactivatedException.class);
        entityManager.flush();
        entityManager.clear();
        assertThat(jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id)).isEqualTo(beforeAccount);
        assertThat(history("student_team_membership", id)).isEqualTo(beforeMemberships);
        assertThat(history("student_deactivation", id)).isEqualTo(beforeDepartures);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void persistenceFailureRollsBackAccountTeamAndHistoryInAnIndependentTransaction() {
        // Setup/observations use separate transactions; the invocation gets NO test-supplied transaction.
        var tx = new TransactionTemplate(transactionManager);
        Integer sectionId = student.getSection().getSectionId();
        Integer teamId = student.getTeam().getTeamId();
        Integer id = tx.execute(status -> {
            Student fixture = new Student("rollback-audit", "Rollback", "Audit", "rollback@audit.example", "hashed", true, "student");
            fixture.setSection(sections.findById(sectionId).orElseThrow());
            teams.findById(teamId).orElseThrow().addStudent(fixture);
            students.saveAndFlush(fixture);
            memberships.saveAndFlush(new StudentTeamMembership(fixture, fixture.getTeam(), fixture.getSection(), LocalDateTime.of(2023, 8, 1, 0, 0)));
            return fixture.getId();
        });
        boolean constraintAdded = false;
        try {
            var beforeAccount = tx.execute(status -> jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id));
            var beforeMemberships = tx.execute(status -> history("student_team_membership", id));
            // A real constraint violation at the account update, after the departure INSERT reached MySQL.
            jdbc.execute("ALTER TABLE peer_evaluation_user ADD CONSTRAINT audit_reject_student_deactivation "
                    + "CHECK (email <> 'rollback@audit.example' OR enabled = 1)");
            constraintAdded = true;
            assertThatThrownBy(() -> service.deactivateStudent(id)).isInstanceOf(RuntimeException.class)
                    .hasRootCauseMessage("Check constraint 'audit_reject_student_deactivation' is violated.");
            tx.executeWithoutResult(status -> {
                assertThat(jdbc.queryForMap("select * from peer_evaluation_user where id = ?", id)).isEqualTo(beforeAccount);
                assertThat(history("student_team_membership", id)).isEqualTo(beforeMemberships);
                assertThat(history("student_deactivation", id)).isEmpty();
            });
        } finally {
            if (constraintAdded) jdbc.execute("ALTER TABLE peer_evaluation_user DROP CHECK audit_reject_student_deactivation");
            tx.executeWithoutResult(status -> {
                jdbc.update("delete from student_deactivation where student_id = ?", id);
                jdbc.update("delete from student_team_membership where student_id = ?", id);
                jdbc.update("delete from peer_evaluation_user where id = ?", id);
            });
        }
    }

    Map<String, Object> archivedMembership() {
        var past = new StudentTeamMembership(student, student.getTeam(), student.getSection(), LocalDateTime.of(2023, 8, 1, 0, 0));
        past.close(LocalDateTime.of(2023, 8, 2, 0, 0));
        memberships.saveAndFlush(past);
        return jdbc.queryForMap("select * from student_team_membership where id = ?", past.getId());
    }

    void assertArchiveUnchanged(Map<String, Object> archive) {
        assertThat(jdbc.queryForMap("select * from student_team_membership where id = ?", archive.get("id"))).isEqualTo(archive);
    }

    @Test
    void reassignmentClosesCurrentIntervalAndPreservesPersistedHistory() {
        var archive = archivedMembership();
        Long oldInterval = memberships.findByStudentIdAndLeftAtIsNull(student.getId()).getFirst().getId();
        Team next = new Team("Next audit team", "Audit", "https://audit.example");
        next.setSection(student.getSection());
        teams.saveAndFlush(next);
        Integer nextId = next.getTeamId();
        teamService.assignStudentToTeam(nextId, student.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(students.findById(student.getId()).orElseThrow().getTeam().getTeamId()).isEqualTo(nextId);
        assertThat(memberships.findById(oldInterval).orElseThrow().getLeftAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        assertThat(memberships.findByStudentIdAndLeftAtIsNull(student.getId())).singleElement()
                .satisfies(m -> assertThat(m.getTeam().getTeamId()).isEqualTo(nextId));
        assertArchiveUnchanged(archive);
    }

    @Test
    void removalClosesCurrentIntervalAndPreservesPersistedHistory() {
        var archive = archivedMembership();
        Long oldInterval = memberships.findByStudentIdAndLeftAtIsNull(student.getId()).getFirst().getId();
        teamService.removeStudentFromTeam(student.getTeam().getTeamId(), student.getId());
        entityManager.flush();
        entityManager.clear();
        assertThat(students.findById(student.getId()).orElseThrow().getTeam()).isNull();
        assertThat(memberships.findById(oldInterval).orElseThrow().getLeftAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        assertThat(memberships.findByStudentIdAndLeftAtIsNull(student.getId())).isEmpty();
        assertArchiveUnchanged(archive);
    }

    @Test
    void sectionTransferClosesOldSectionIntervalAndPreservesPersistedHistory() {
        var archive = archivedMembership();
        Integer oldSectionId = student.getSection().getSectionId();
        Long oldInterval = memberships.findByStudentIdAndLeftAtIsNull(student.getId()).getFirst().getId();
        Section next = new Section();
        next.setSectionName("Audit destination");
        next.setCourse(student.getSection().getCourse());
        next.addInstructor(student.getSection().getInstructors().iterator().next());
        sections.saveAndFlush(next);
        Integer nextId = next.getSectionId();
        teamService.transferTeamToAnotherSection(student.getTeam().getTeamId(), nextId);
        entityManager.flush();
        entityManager.clear();
        assertThat(students.findById(student.getId()).orElseThrow().getSection().getSectionId()).isEqualTo(nextId);
        var old = memberships.findById(oldInterval).orElseThrow();
        assertThat(old.getSection().getSectionId()).isEqualTo(oldSectionId);
        assertThat(old.getLeftAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        assertThat(memberships.findByStudentIdAndLeftAtIsNull(student.getId())).singleElement()
                .satisfies(m -> assertThat(m.getSection().getSectionId()).isEqualTo(nextId));
        assertArchiveUnchanged(archive);
    }
}
