package kr.onsil.repo;

import kr.onsil.domain.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @Query("select m from ChatMessage m join fetch m.user where m.classroom.id = :classId order by m.id desc")
    List<ChatMessage> findRecent(Long classId, Pageable page);
}
