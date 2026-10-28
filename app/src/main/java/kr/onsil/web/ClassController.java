package kr.onsil.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.onsil.domain.ClassRole;
import kr.onsil.domain.Classroom;
import kr.onsil.domain.Membership;
import kr.onsil.security.AppUser;
import kr.onsil.service.ClassService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/classes")
public class ClassController {
    private final ClassService classService;

    public ClassController(ClassService classService) { this.classService = classService; }

    public record ClassSummary(Long id, String name, ClassRole myRole, String teacherName, String inviteCode) {
        static ClassSummary of(Membership m) {
            Classroom c = m.getClassroom();
            String code = m.getRole() == ClassRole.TEACHER ? c.getInviteCode() : null;
            return new ClassSummary(c.getId(), c.getName(), m.getRole(), c.getOwner().getDisplayName(), code);
        }
    }

    public record MemberDto(Long userId, String username, String displayName, ClassRole role) {}
    public record CreateRequest(@NotBlank(message = "클래스 이름을 입력하세요.") @Size(max = 60) String name) {}
    public record JoinRequest(@NotBlank(message = "초대 코드를 입력하세요.") String code) {}
    public record RoleRequest(@NotNull ClassRole role) {}

    @GetMapping
    public List<ClassSummary> myClasses(@AuthenticationPrincipal AppUser me) {
        return classService.myClasses(me.getId()).stream().map(ClassSummary::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClassSummary create(@AuthenticationPrincipal AppUser me, @Valid @RequestBody CreateRequest req) {
        Classroom c = classService.create(me.getId(), req.name().trim());
        return ClassSummary.of(classService.requireMember(c.getId(), me.getId()));
    }

    @PostMapping("/join")
    public ClassSummary join(@AuthenticationPrincipal AppUser me, @Valid @RequestBody JoinRequest req) {
        Membership m = classService.join(me.getId(), req.code());
        return ClassSummary.of(classService.requireMember(m.getClassroom().getId(), me.getId()));
    }

    @GetMapping("/{classId}")
    public ClassSummary get(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        return ClassSummary.of(classService.requireMember(classId, me.getId()));
    }

    @PostMapping("/{classId}/invite-code")
    public Map<String, String> regenerateCode(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        return Map.of("inviteCode", classService.regenerateCode(classId, me.getId()));
    }

    @GetMapping("/{classId}/members")
    public List<MemberDto> members(@AuthenticationPrincipal AppUser me, @PathVariable Long classId) {
        classService.requireMember(classId, me.getId());
        return classService.members(classId).stream()
                .map(m -> new MemberDto(m.getUser().getId(), m.getUser().getUsername(),
                        m.getUser().getDisplayName(), m.getRole()))
                .toList();
    }

    @PutMapping("/{classId}/members/{userId}/role")
    public void setRole(@AuthenticationPrincipal AppUser me, @PathVariable Long classId,
                        @PathVariable Long userId, @Valid @RequestBody RoleRequest req) {
        classService.setRole(classId, me.getId(), userId, req.role());
    }
}
