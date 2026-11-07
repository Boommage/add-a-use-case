package team.projectpulse.student;

import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.userinvitation.UserInvitationService;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import team.projectpulse.team.Team;
import team.projectpulse.system.WeekKeys;
import team.projectpulse.system.exception.StudentAlreadyDeactivatedException;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class StudentService {

    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final UserInvitationService userInvitationService;
    private final PasswordEncoder passwordEncoder;
    private final UserUtils userUtils;
    private final Clock clock;
    private final EntityManager entityManager;
    private final StudentTeamMembershipRepository membershipRepository;
    private final StudentDeactivationRepository deactivationRepository;


    public StudentService(StudentRepository studentRepository, SectionRepository sectionRepository, UserInvitationService userInvitationService, PasswordEncoder passwordEncoder, UserUtils userUtils, Clock clock, StudentTeamMembershipRepository membershipRepository, StudentDeactivationRepository deactivationRepository, EntityManager entityManager) {
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.userInvitationService = userInvitationService;
        this.passwordEncoder = passwordEncoder;
        this.userUtils = userUtils;
        this.clock = clock;
        this.entityManager = entityManager;
        this.membershipRepository = membershipRepository;
        this.deactivationRepository = deactivationRepository;
    }

    public Page<Student> findByCriteria(Map<String, String> searchCriteria, Pageable pageable) {
        Integer sectionId = this.userUtils.getUserSectionId();

        Specification<Student> spec = Specification.unrestricted(); // Start with an unrestricted specification, matching all objects.

        if (StringUtils.hasLength(searchCriteria.get("firstName"))) {
            spec = spec.and(StudentSpecs.containsFirstName(searchCriteria.get("firstName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("lastName"))) {
            spec = spec.and(StudentSpecs.containsLastName(searchCriteria.get("lastName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("email"))) {
            spec = spec.and(StudentSpecs.hasEmail(searchCriteria.get("email")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamName"))) {
            spec = spec.and(StudentSpecs.hasTeamName(searchCriteria.get("teamName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamId"))) {
            spec = spec.and(StudentSpecs.hasTeamId(searchCriteria.get("teamId")));
        }

        if (StringUtils.hasLength(searchCriteria.get("sectionName"))) {
            spec = spec.and(StudentSpecs.hasSectionName(searchCriteria.get("sectionName")));
        }

        spec = spec.and(StudentSpecs.belongsToSection(sectionId)); // Only show students that belong to the default section of the instructor

        return this.studentRepository.findAll(spec, pageable);
    }

    public Student findStudentById(Integer studentId) {
        return this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
    }

    public List<Student> findStudentsByTeamId(Integer teamId) {
        return this.studentRepository.findByTeamTeamId(teamId);
    }

    public Student saveStudent(Student newStudent, Integer courseId, Integer sectionId, String registrationToken, String role) {
        // Validate user invitation
        this.userInvitationService.validateUserInvitation(newStudent.getEmail(), registrationToken, courseId, sectionId, role);

        // Password rule: At least 6 characters, contains at least one letter and one number.
        if (!newStudent.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,}$")) {
            throw new IllegalArgumentException("Password must contain at least one letter and one number, and at least 6 characters.");
        }

        // Add the student to the section
        Section section = this.sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ObjectNotFoundException("section", sectionId));
        section.addStudent(newStudent);

        newStudent.setEnabled(true);
        newStudent.setRoles("student");
        newStudent.setPassword(this.passwordEncoder.encode(newStudent.getPassword()));

        Student savedStudent = this.studentRepository.save(newStudent);
        // Delete the user invitation after the student is saved.
        this.userInvitationService.deleteUserInvitation(newStudent.getEmail());
        return savedStudent;
    }

    public Student deactivateStudent(Integer studentId) {
        Student student = this.studentRepository.findForLifecycleUpdate(studentId, this.userUtils.getUserId(),
                        this.userUtils.hasRole("ROLE_admin"), this.userUtils.hasRole("ROLE_instructor"))
                .orElseThrow(() -> new AccessDeniedException("No permission to deactivate this student."));
        // A route check may already have loaded this entity into the persistence context.
        this.entityManager.refresh(student, LockModeType.PESSIMISTIC_WRITE);
        if (!this.studentRepository.existsInLifecycleScope(studentId, this.userUtils.getUserId(),
                this.userUtils.hasRole("ROLE_admin"), this.userUtils.hasRole("ROLE_instructor"))) {
            throw new AccessDeniedException("No permission to deactivate this student.");
        }
        if (!student.isEnabled()) throw new StudentAlreadyDeactivatedException();
        LocalDateTime now = LocalDateTime.now(this.clock.withZone(ZoneOffset.UTC));
        this.deactivationRepository.save(new StudentDeactivation(student, now, WeekKeys.of(LocalDate.now(this.clock))));
        closeMemberships(student, now);
        if (student.getTeam() != null) student.getTeam().removeStudent(student);
        student.setEnabled(false);
        return this.studentRepository.save(student);
    }

    /** The caller holds the student's write lock; history and assignment share its transaction. */
    public void recordMembershipTransition(Student student, Team target) {
        LocalDateTime now = LocalDateTime.now(this.clock.withZone(ZoneOffset.UTC));
        closeMemberships(student, now);
        if (target != null) {
            this.membershipRepository.save(new StudentTeamMembership(student, target, student.getSection(), now));
        }
    }

    private void closeMemberships(Student student, LocalDateTime now) {
        this.membershipRepository.findByStudentIdAndLeftAtIsNull(student.getId()).forEach(m -> m.close(now));
    }

    public Student updateStudent(Integer studentId, Student update) {
        Student oldStudent = this.studentRepository.findForUpdate(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));

        this.entityManager.refresh(oldStudent, LockModeType.PESSIMISTIC_WRITE);
        if (oldStudent.isEnabled() && !update.isEnabled()) {
            if (!this.studentRepository.existsInLifecycleScope(studentId, this.userUtils.getUserId(),
                    this.userUtils.hasRole("ROLE_admin"), this.userUtils.hasRole("ROLE_instructor"))) {
                throw new AccessDeniedException("No permission to deactivate this student.");
            }
            throw new IllegalArgumentException("Student deactivation requires the deactivation endpoint. No changes were saved.");
        }

        // If the user is not an admin, then the user can only update her username, first rubricName, last rubricName, and email
        oldStudent.setUsername(update.getUsername());
        oldStudent.setFirstName(update.getFirstName());
        oldStudent.setLastName(update.getLastName());
        oldStudent.setEmail(update.getEmail());

        // Reactivation retains the existing profile-update behavior.
        if (this.userUtils.hasRole("ROLE_instructor")) {
            oldStudent.setEnabled(update.isEnabled());
//            oldStudent.setRoles(update.getRoles());
        }

        return this.studentRepository.save(oldStudent);
    }

    public void deleteStudent(Integer studentId) {
        this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
        this.studentRepository.deleteById(studentId);
    }

}
