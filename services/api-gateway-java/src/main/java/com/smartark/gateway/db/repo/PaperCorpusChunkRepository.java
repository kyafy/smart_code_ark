package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.PaperCorpusChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaperCorpusChunkRepository extends JpaRepository<PaperCorpusChunkEntity, Long> {
    List<PaperCorpusChunkEntity> findByDocId(Long docId);
    Optional<PaperCorpusChunkEntity> findByChunkUid(String chunkUid);
    void deleteByDocIdIn(List<Long> docIds);
}
