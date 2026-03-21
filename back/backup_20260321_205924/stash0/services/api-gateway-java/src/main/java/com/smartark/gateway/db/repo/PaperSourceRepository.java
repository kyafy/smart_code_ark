package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperSourceRepository extends JpaRepository<PaperSourceEntity, Long> {
    List<PaperSourceEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
    void deleteBySessionId(Long sessionId);
}
