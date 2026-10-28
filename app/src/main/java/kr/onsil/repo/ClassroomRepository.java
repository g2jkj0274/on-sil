package kr.onsil.repo;

import kr.onsil.domain.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClassroomRepository extends JpaRepository<Classroom, Long> {
    Optional<Classroom> findByInviteCode(String inviteCode);
    boolean existsByInviteCode(String inviteCode);
}
