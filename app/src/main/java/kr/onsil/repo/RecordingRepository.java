package kr.onsil.repo;

import kr.onsil.domain.Recording;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RecordingRepository extends JpaRepository<Recording, Long> {
    @Query("select r from Recording r join fetch r.createdBy where r.classroom.id = :classId order by r.id desc")
    List<Recording> findByClass(Long classId);

    List<Recording> findByStatus(Recording.Status status);
}
