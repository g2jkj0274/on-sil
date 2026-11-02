package kr.onsil.repo;

import kr.onsil.domain.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    @Query("select m from Material m join fetch m.uploader where m.classroom.id = :classId order by m.id desc")
    List<Material> findByClass(Long classId);
}
