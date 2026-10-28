package kr.onsil.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.onsil.domain.AccountType;
import kr.onsil.domain.User;
import kr.onsil.repo.UserRepository;
import kr.onsil.security.AppUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public record SignupRequest(
            @NotBlank(message = "아이디를 입력하세요.")
            @Pattern(regexp = "[A-Za-z0-9_]{3,20}", message = "아이디는 영문/숫자/_ 3~20자입니다.") String username,
            @NotBlank(message = "비밀번호를 입력하세요.")
            @Size(min = 4, max = 64, message = "비밀번호는 4자 이상입니다.") String password,
            @NotBlank(message = "이름을 입력하세요.")
            @Size(max = 20, message = "이름은 20자 이하입니다.") String displayName,
            @NotNull(message = "강사/학생을 선택하세요.") AccountType accountType) {}

    public record MeResponse(Long id, String username, String displayName, AccountType accountType) {}

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public MeResponse signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByUsername(req.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다.");
        }
        User u = users.save(new User(req.username(), encoder.encode(req.password()),
                req.displayName().trim(), req.accountType()));
        return new MeResponse(u.getId(), u.getUsername(), u.getDisplayName(), u.getAccountType());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUser me) {
        return new MeResponse(me.getId(), me.getUsername(), me.getDisplayName(), me.getAccountType());
    }
}
