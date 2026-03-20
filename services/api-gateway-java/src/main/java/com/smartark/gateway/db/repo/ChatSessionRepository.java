package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ChatSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {
    List<ChatSessionEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);
    List<ChatSessionEntity> findByUserIdAndStatusNotOrderByUpdatedAtDesc(Long userId, String status);
}
