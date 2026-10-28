package kr.onsil.repo;

import kr.onsil.domain.Membership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MembershipRepository extends JpaRepository<Membership, Long> {
    @Query("select m from Membership m join fetch m.classroom c join fetch c.owner where m.user.id = :userId order by c.id")
    List<Membership> findByUserIdWithClass(Long userId);

    @Query("select m from Membership m join fetch m.user where m.classroom.id = :classId")
    List<Membership> findByClassIdWithUser(Long classId);

    Optional<Membership> findByClassroomIdAndUserId(Long classroomId, Long userId);
}
