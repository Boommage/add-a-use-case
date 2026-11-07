package team.projectpulse.student;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.StudentAlreadyDeactivatedException;
import team.projectpulse.team.Team;
import team.projectpulse.user.userinvitation.UserInvitationService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student: shared lifecycle invariants; database atomicity is integration-tested separately. */
@ExtendWith(MockitoExtension.class)
class StudentLifecycleServiceTest {
    @Mock jakarta.persistence.EntityManager entityManager;
    @Mock StudentRepository students;
    @Mock SectionRepository sections;
    @Mock UserInvitationService invitations;
    @Mock PasswordEncoder passwords;
    @Mock UserUtils user;
    @Mock StudentTeamMembershipRepository memberships;
    @Mock StudentDeactivationRepository departures;
    StudentService service;
    Student student;
    Section section;
    Team team;
    final Clock clock = Clock.fixed(Instant.parse("2027-01-01T12:00:00Z"), ZoneOffset.UTC);
    final LocalDateTime now = LocalDateTime.now(clock);

    @BeforeEach
    void setUp() {
        service = new StudentService(students, sections, invitations, passwords, user, clock, memberships, departures, entityManager);
        student = new Student("student", "Jane", "Doe", "jane@example.edu", "hashed", true, "student");
        student.setId(10);
        section = new Section();
        section.setSectionId(2);
        section.addStudent(student);
        team = new Team();
        team.setTeamId(3);
        team.setSection(section);
    }

    void allowInstructor() {
        when(user.getUserId()).thenReturn(7);
        when(user.hasRole("ROLE_admin")).thenReturn(false);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findForLifecycleUpdate(10, 7, false, true)).thenReturn(Optional.of(student));
        when(students.existsInLifecycleScope(10, 7, false, true)).thenReturn(true);
    }

    @Test
    void deactivationRetainsIdentityAndSectionButClosesMembershipAndRemovesTeam() {
        allowInstructor();
        team.addStudent(student);
        var membership = new StudentTeamMembership(student, team, section, now.minusWeeks(2));
        when(memberships.findByStudentIdAndLeftAtIsNull(10)).thenReturn(List.of(membership));
        when(students.save(student)).thenReturn(student);
        assertThat(service.deactivateStudent(10)).isSameAs(student);
        assertThat(student.isEnabled()).isFalse();
        assertThat(student.getTeam()).isNull();
        assertThat(team.getStudents()).doesNotContain(student);
        assertThat(student.getSection()).isSameAs(section);
        assertThat(student.getEmail()).isEqualTo("jane@example.edu");
        assertThat(student.getPassword()).isEqualTo("hashed");
        assertThat(membership.getLeftAt()).isEqualTo(now);
        var departure = org.mockito.ArgumentCaptor.forClass(StudentDeactivation.class);
        verify(departures).save(departure.capture());
        assertThat(departure.getValue().getFormerTeam()).isSameAs(team);
        assertThat(departure.getValue().getSection()).isSameAs(section);
        assertThat(departure.getValue().getCutoffWeek()).isEqualTo("2026-W53");
        assertThat(departure.getValue().getDeactivatedAt()).isEqualTo(now);
        verify(students, never()).deleteById(any());
    }

    @Test
    void cutoffUsesCourseTimezoneWhileHistoryTimestampUsesUtc() {
        Clock chicago = Clock.fixed(Instant.parse("2027-01-04T01:00:00Z"), java.time.ZoneId.of("America/Chicago"));
        service = new StudentService(students, sections, invitations, passwords, user, chicago, memberships, departures, entityManager);
        allowInstructor();
        service.deactivateStudent(10);
        var departure = org.mockito.ArgumentCaptor.forClass(StudentDeactivation.class);
        verify(departures).save(departure.capture());
        assertThat(departure.getValue().getCutoffWeek()).isEqualTo("2026-W53");
        assertThat(departure.getValue().getDeactivatedAt()).isEqualTo(LocalDateTime.parse("2027-01-04T01:00:00"));
    }

    @Test
    void owningAdminCanDeactivateStudentWithoutTeam() {
        when(user.getUserId()).thenReturn(7);
        when(user.hasRole("ROLE_admin")).thenReturn(true);
        when(user.hasRole("ROLE_instructor")).thenReturn(false);
        when(students.findForLifecycleUpdate(10, 7, true, false)).thenReturn(Optional.of(student));
        when(students.existsInLifecycleScope(10, 7, true, false)).thenReturn(true);
        when(memberships.findByStudentIdAndLeftAtIsNull(10)).thenReturn(List.of());
        service.deactivateStudent(10);
        assertThat(student.isEnabled()).isFalse();
        var departure = org.mockito.ArgumentCaptor.forClass(StudentDeactivation.class);
        verify(departures).save(departure.capture());
        assertThat(departure.getValue().getFormerTeam()).isNull();
        verify(memberships, never()).save(any());
    }

    @Test
    void unresolvedOrOutOfScopeTargetIsDeniedWithoutMutation() {
        when(students.findForLifecycleUpdate(10, 0, false, false)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateStudent(10)).isInstanceOf(AccessDeniedException.class);
        assertThat(student.isEnabled()).isTrue();
        verifyNoInteractions(departures, memberships);
        verify(students, never()).save(any());
    }

    @Test
    void alreadyInactiveIsConflictWithoutAnotherDeparture() {
        allowInstructor();
        student.setEnabled(false);
        assertThatThrownBy(() -> service.deactivateStudent(10)).isInstanceOf(StudentAlreadyDeactivatedException.class);
        verifyNoInteractions(departures, memberships);
        verify(students, never()).save(any());
    }

    @Test
    void authorizedProfileBypassRejectsEntireUpdate() {
        when(user.getUserId()).thenReturn(7);
        when(user.hasRole("ROLE_admin")).thenReturn(false);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(students.findForUpdate(10)).thenReturn(Optional.of(student));
        when(students.existsInLifecycleScope(10, 7, false, true)).thenReturn(true);
        Student update = new Student("changed", "Changed", "Name", "changed@example.edu", "", false, "student");
        assertThatThrownBy(() -> service.updateStudent(10, update)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Student deactivation requires the deactivation endpoint. No changes were saved.");
        assertThat(student.getUsername()).isEqualTo("student");
        assertThat(student.getEmail()).isEqualTo("jane@example.edu");
        assertThat(student.isEnabled()).isTrue();
        verify(students, never()).save(any());
    }

    @Test
    void unauthorizedProfileBypassIsForbiddenBeforeProfileMutation() {
        when(students.findForUpdate(10)).thenReturn(Optional.of(student));
        assertThatThrownBy(() -> service.updateStudent(10, new Student())).isInstanceOf(AccessDeniedException.class);
        assertThat(student.getUsername()).isEqualTo("student");
        verify(students, never()).save(any());
    }

    @Test
    void reassignmentClosesCurrentIntervalAndAppendsAnotherWithoutOverwritingHistory() {
        var current = new StudentTeamMembership(student, team, section, now.minusDays(3));
        var past = new StudentTeamMembership(student, team, section, now.minusWeeks(2));
        past.close(now.minusWeeks(1));
        when(memberships.findByStudentIdAndLeftAtIsNull(10)).thenReturn(List.of(current));
        Team newTeam = new Team();
        newTeam.setTeamId(4);
        service.recordMembershipTransition(student, newTeam);
        assertThat(current.getLeftAt()).isEqualTo(now);
        assertThat(past.getLeftAt()).isEqualTo(now.minusWeeks(1));
        var added = org.mockito.ArgumentCaptor.forClass(StudentTeamMembership.class);
        verify(memberships).save(added.capture());
        assertThat(added.getValue().getTeam()).isSameAs(newTeam);
        assertThat(added.getValue().getJoinedAt()).isEqualTo(now);
        assertThat(added.getValue().getLeftAt()).isNull();
        verifyNoInteractions(departures);
    }

    @Test
    void removalClosesMembershipWithoutCreatingAnotherInterval() {
        var current = new StudentTeamMembership(student, team, section, now.minusDays(3));
        when(memberships.findByStudentIdAndLeftAtIsNull(10)).thenReturn(List.of(current));
        service.recordMembershipTransition(student, null);
        assertThat(current.getLeftAt()).isEqualTo(now);
        verify(memberships, never()).save(any());
    }

    @Test
    void membershipIntervalsRejectClosingTwiceOrBeforeJoining() {
        var interval = new StudentTeamMembership(student, team, section, now);
        assertThatThrownBy(() -> interval.close(now.minusSeconds(1))).isInstanceOf(IllegalStateException.class);
        interval.close(now);
        assertThatThrownBy(() -> interval.close(now.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
}
