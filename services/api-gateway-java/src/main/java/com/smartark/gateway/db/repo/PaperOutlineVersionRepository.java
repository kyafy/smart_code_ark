package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaperOutlineVersionRepository extends JpaRepository<PaperOutlineVersionEntity, Long> {
    Optional<PaperOutlineVersionEntity> findTopBySessionIdOrderByVersionNoDesc(Long sessionId);

    @Query(value = """
            SELECT pov.*
            FROM paper_outline_versions pov
            INNER JOIN paper_topic_session pts ON pts.id = pov.session_id
            WHERE pts.task_id = :taskId
            ORDER BY pov.version_no DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<PaperOutlineVersionEntity> findTopByTaskIdOrderByVersionNoDesc(@Param("taskId") String taskId);
}
