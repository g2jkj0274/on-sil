package kr.onsil.service;

import kr.onsil.domain.*;
import kr.onsil.repo.ClassroomRepository;
import kr.onsil.repo.MembershipRepository;
import kr.onsil.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;

/** 클래스와 멤버십, 그리고 "이 사람이 이 클래스에서 무엇을 할 수 있는가" 검사. */
@Service
@Transactional
public class ClassService {
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final ClassroomRepository classrooms;
    private final MembershipRepository memberships;
    private final UserRepository users;

    public ClassService(ClassroomRepository classrooms, MembershipRepository memberships, UserRepository users) {
        this.classrooms = classrooms;
        this.memberships = memberships;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Membership> myClasses(Long userId) { return memberships.findByUserIdWithClass(userId); }

    public Classroom create(Long teacherId, String name) {
        User teacher = users.findById(teacherId).orElseThrow();
        if (teacher.getAccountType() != AccountType.TEACHER) throw forbidden("강사만 클래스를 만들 수 있습니다.");
        return createWithCode(teacher, name, newInviteCode());
    }

    public Classroom createWithCode(User teacher, String name, String code) {
        Classroom c = classrooms.save(new Classroom(name, code, teacher));
        memberships.save(new Membership(c, teacher, ClassRole.TEACHER));
        return c;
    }

    public Membership join(Long userId, String code) {
        Classroom c = classrooms.findByInviteCode(code.trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "초대 코드가 올바르지 않습니다."));
        return memberships.findByClassroomIdAndUserId(c.getId(), userId).orElseGet(() ->
                memberships.save(new Membership(c, users.findById(userId).orElseThrow(), ClassRole.STUDENT)));
    }

    public String regenerateCode(Long classId, Long userId) {
        Membership m = requireRole(classId, userId, ClassRole.TEACHER);
        m.getClassroom().setInviteCode(newInviteCode());
        return m.getClassroom().getInviteCode();
    }

    @Transactional(readOnly = true)
    public List<Membership> members(Long classId) {
        return memberships.findByClassIdWithUser(classId).stream()
                .sorted(Comparator.comparing((Membership m) -> m.getRole().ordinal())
                        .thenComparing(m -> m.getUser().getDisplayName()))
                .toList();
    }

    /** 강사가 학생을 조교로 지정하거나 해제한다. */
    public void setRole(Long classId, Long teacherId, Long targetUserId, ClassRole role) {
        requireRole(classId, teacherId, ClassRole.TEACHER);
        if (role == ClassRole.TEACHER) throw forbidden("강사 역할은 지정할 수 없습니다.");
        Membership target = memberships.findByClassroomIdAndUserId(classId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "클래스 멤버가 아닙니다."));
        if (target.getRole() == ClassRole.TEACHER) throw forbidden("강사의 역할은 바꿀 수 없습니다.");
        target.setRole(role);
    }

    /** 클래스 멤버가 아니면 403. */
    @Transactional(readOnly = true)
    public Membership requireMember(Long classId, Long userId) {
        Membership m = memberships.findByClassroomIdAndUserId(classId, userId)
                .orElseThrow(() -> forbidden("이 클래스의 멤버가 아닙니다."));
        m.getClassroom().getOwner().getDisplayName(); // 지연 로딩 초기화
        return m;
    }

    @Transactional(readOnly = true)
    public Membership requireStaff(Long classId, Long userId) {
        Membership m = requireMember(classId, userId);
        if (!m.getRole().isStaff()) throw forbidden("강사·조교만 할 수 있습니다.");
        return m;
    }

    @Transactional(readOnly = true)
    public Membership requireRole(Long classId, Long userId, ClassRole role) {
        Membership m = requireMember(classId, userId);
        if (m.getRole() != role) throw forbidden("권한이 없습니다.");
        return m;
    }

    private String newInviteCode() {
        for (;;) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            if (!classrooms.existsByInviteCode(sb.toString())) return sb.toString();
        }
    }

    private static ResponseStatusException forbidden(String msg) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, msg);
    }
}
