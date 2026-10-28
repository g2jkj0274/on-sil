package kr.onsil.service;

import kr.onsil.domain.*;
import kr.onsil.repo.MembershipRepository;
import kr.onsil.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** DB가 비어 있으면 테스트 계정과 데모 클래스를 만든다. */
@Component
public class DataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final ClassService classService;
    private final PasswordEncoder encoder;

    public DataSeeder(UserRepository users, MembershipRepository memberships, ClassService classService, PasswordEncoder encoder) {
        this.users = users;
        this.memberships = memberships;
        this.classService = classService;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        String pw = encoder.encode("1234");
        User teacher = users.save(new User("teacher1", pw, "김강사", AccountType.TEACHER));
        User ta = users.save(new User("ta1", pw, "이조교", AccountType.STUDENT));
        Classroom demo = classService.createWithCode(teacher, "데모 클래스", "DEMO01");
        memberships.save(new Membership(demo, ta, ClassRole.TA));
        String[] names = {"박학생", "최학생", "정학생", "강학생"};
        for (int i = 0; i < names.length; i++) {
            User s = users.save(new User("student" + (i + 1), pw, names[i], AccountType.STUDENT));
            memberships.save(new Membership(demo, s, ClassRole.STUDENT));
        }
        log.info("시드 데이터 생성: teacher1, ta1, student1~4 (비밀번호 1234), 데모 클래스(초대 코드 DEMO01)");
    }
}
