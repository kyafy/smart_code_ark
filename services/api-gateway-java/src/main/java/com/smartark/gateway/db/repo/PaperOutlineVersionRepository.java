package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaperOutlineVersionRepository extends JpaRepository<PaperOutlineVersionEntity, Long> {
    Optional<PaperOutlineVersionEntity> findTopBySessionIdOrderByVersionNoDesc(Long sessionId);
}
