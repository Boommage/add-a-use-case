package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.system.UserUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentSecurityServiceTest {
    @Mock StudentRepository students;
    @Mock UserUtils user;
    @InjectMocks StudentSecurityService security;

    @Test
    void missingTargetFailsClosed() {
        assertThat(security.mayManageLifecycle(null)).isFalse();
        verifyNoInteractions(students, user);
    }

    @Test
    void studentCannotManageLifecycleEvenForSelf() {
        assertThat(security.mayManageLifecycle(10)).isFalse();
        verifyNoInteractions(students);
    }

    @Test
    void adminStillNeedsPersistedCourseOrCourseSectionScope() {
        when(user.hasRole("ROLE_admin")).thenReturn(true);
        when(user.hasRole("ROLE_instructor")).thenReturn(false);
        when(user.getUserId()).thenReturn(7);
        when(students.existsInLifecycleScope(10, 7, true, false)).thenReturn(false);
        assertThat(security.mayManageLifecycle(10)).isFalse();
    }

    @Test
    void assignedInstructorIsAdmittedByScopedQuery() {
        when(user.hasRole("ROLE_admin")).thenReturn(false);
        when(user.hasRole("ROLE_instructor")).thenReturn(true);
        when(user.getUserId()).thenReturn(7);
        when(students.existsInLifecycleScope(10, 7, false, true)).thenReturn(true);
        assertThat(security.mayManageLifecycle(10)).isTrue();
    }
}
