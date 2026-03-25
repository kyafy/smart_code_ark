package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperCorpusDocEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaperCorpusDocRepository extends JpaRepository<PaperCorpusDocEntity, Long> {
    List<PaperCorpusDocEntity> findBySessionId(Long sessionId);
    void deleteBySessionId(Long sessionId);
    long countBySessionId(Long sessionId);
}
