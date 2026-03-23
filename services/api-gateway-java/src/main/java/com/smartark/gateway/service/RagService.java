package com.smartark.gateway.service;

import com.smartark.gateway.agent.model.QdrantChunkPayload;
import com.smartark.gateway.agent.model.QdrantSearchResult;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperCorpusChunkEntity;
import com.smartark.gateway.db.entity.PaperCorpusDocEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.repo.PaperCorpusChunkRepository;
import com.smartark.gateway.db.repo.PaperCorpusDocRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.dto.RagStatsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class RagService {
    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingService embeddingService;
    private final TextChunkService textChunkService;
    private final QdrantService qdrantService;
    private final PaperCorpusDocRepository corpusDocRepository;
    private final PaperCorpusChunkRepository corpusChunkRepository;
    private final PaperSourceRepository paperSourceRepository;

    @Value("${smartark.rag.chunk-max-tokens:512}")
    private int chunkMaxTokens;

    @Value("${smartark.rag.chunk-overlap-tokens:64}")
    private int chunkOverlapTokens;

    @Value("${smartark.rag.retrieve-top-k:30}")
    private int retrieveTopK;

    @Value("${smartark.rag.rerank-top-n:15}")
    private int rerankTopN;

    public RagService(
            EmbeddingService embeddingService,
            TextChunkService textChunkService,
            QdrantService qdrantService,
            PaperCorpusDocRepository corpusDocRepository,
            PaperCorpusChunkRepository corpusChunkRepository,
            PaperSourceRepository paperSourceRepository
    ) {
        this.embeddingService = embeddingService;
        this.textChunkService = textChunkService;
        this.qdrantService = qdrantService;
        this.corpusDocRepository = corpusDocRepository;
        this.corpusChunkRepository = corpusChunkRepository;
        this.paperSourceRepository = paperSourceRepository;
    }

    public record RagIndexResult(int chunkCount, int docCount) {}

    @Transactional
    public RagIndexResult indexSources(Long sessionId, List<PaperSourceEntity> sources, String discipline) {
        if (sources == null || sources.isEmpty()) {
            logger.info("RAG index skipped: sessionId={}, reason=no_sources", sessionId);
            return new RagIndexResult(0, 0);
        }

        int totalChunks = 0;
        int totalDocs = 0;
        List<QdrantChunkPayload> allPayloads = new ArrayList<>();
        List<String> allTexts = new ArrayList<>();
        List<QdrantChunkPayload> payloadRefs = new ArrayList<>();

        for (PaperSourceEntity source : sources) {
            String docUid = UUID.randomUUID().toString().replace("-", "");
            LocalDateTime now = LocalDateTime.now();

            PaperCorpusDocEntity doc = new PaperCorpusDocEntity();
            doc.setSessionId(sessionId);
            doc.setDocUid(docUid);
            doc.setPaperId(source.getPaperId());
            doc.setTitle(source.getTitle());
            doc.setYear(source.getYear());
            doc.setDiscipline(discipline);
            doc.setUrl(source.getUrl());
            doc.setLanguage("zh");
            doc.setSource("semantic_scholar");
            doc.setCitationCount(0);
            doc.setStatus("indexed");
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);

            // Build text to chunk: abstract + evidence snippet
            StringBuilder textBuilder = new StringBuilder();
            if (source.getAbstractText() != null && !source.getAbstractText().isBlank()) {
                textBuilder.append(source.getAbstractText());
            }
            if (source.getEvidenceSnippet() != null && !source.getEvidenceSnippet().isBlank()) {
                if (textBuilder.length() > 0) {
                    textBuilder.append("\n\n");
                }
                textBuilder.append(source.getEvidenceSnippet());
            }

            String fullText = textBuilder.toString();
            if (fullText.isBlank()) {
                continue;
            }

            List<TextChunkService.TextChunk> chunks = textChunkService.chunk(fullText, chunkMaxTokens, chunkOverlapTokens);
            doc.setChunkCount(chunks.size());
            corpusDocRepository.save(doc);

            for (TextChunkService.TextChunk chunk : chunks) {
                String chunkUid = UUID.randomUUID().toString().replace("-", "");

                PaperCorpusChunkEntity chunkEntity = new PaperCorpusChunkEntity();
                chunkEntity.setDocId(doc.getId());
                chunkEntity.setChunkUid(chunkUid);
                chunkEntity.setChunkIndex(chunk.index());
                chunkEntity.setChunkType(chunk.index() == 0 ? "abstract" : "body");
                chunkEntity.setContent(chunk.content());
                chunkEntity.setTokenCount(chunk.tokenCount());
                chunkEntity.setCreatedAt(now);
                corpusChunkRepository.save(chunkEntity);

                QdrantChunkPayload payload = new QdrantChunkPayload();
                payload.setChunkUid(chunkUid);
                payload.setDocUid(docUid);
                payload.setSource("semantic_scholar");
                payload.setTitle(source.getTitle());
                payload.setYear(source.getYear());
                payload.setDiscipline(discipline);
                payload.setUrl(source.getUrl());
                payload.setLanguage("zh");
                payload.setChunkType(chunkEntity.getChunkType());
                payload.setCitationCount(0);
                payload.setContent(chunk.content());
                payload.setPaperId(source.getPaperId());

                allTexts.add(chunk.content());
                payloadRefs.add(payload);
                totalChunks++;
            }
            totalDocs++;
        }

        logger.info("RAG index prepared: sessionId={}, sourceCount={}, docCount={}, chunkCount={}, discipline={}",
                sessionId, sources.size(), totalDocs, totalChunks, discipline);
        logger.info("RAG index source sample: sessionId={}, sample={}", sessionId, buildSourceSample(sources, 5));

        // Batch embed all texts
        if (!allTexts.isEmpty()) {
            try {
                List<float[]> vectors = embeddingService.embed(allTexts);
                logger.info("RAG index embedding done: sessionId={}, vectorCount={}", sessionId, vectors.size());
                for (int i = 0; i < payloadRefs.size(); i++) {
                    payloadRefs.get(i).setVector(vectors.get(i));
                }
                // Batch upsert to Qdrant
                qdrantService.upsertChunks(payloadRefs);
                logger.info("RAG index qdrant upsert done: sessionId={}, upsertCount={}", sessionId, payloadRefs.size());
            } catch (BusinessException e) {
                throw new BusinessException(ErrorCodes.RAG_INDEX_FAILED, "RAG索引构建失败: " + e.getMessage());
            }
        }

        logger.info("RAG indexed {} docs, {} chunks for session {}", totalDocs, totalChunks, sessionId);
        return new RagIndexResult(totalChunks, totalDocs);
    }

    public List<RagEvidenceItem> retrieveAndRerank(Long sessionId, String query, String discipline, int topK) {
        try {
            logger.info("RAG retrieve start: sessionId={}, topK={}, discipline={}, query={}",
                    sessionId, topK > 0 ? topK : retrieveTopK, discipline, truncate(query, 220));
            // 1. Embed query
            List<float[]> queryVectors = embeddingService.embed(List.of(query));
            if (queryVectors.isEmpty()) {
                logger.info("RAG retrieve early return: sessionId={}, reason=empty_query_vector", sessionId);
                return List.of();
            }
            float[] queryVector = queryVectors.get(0);
            logger.info("RAG retrieve query embedded: sessionId={}, queryVectorDim={}", sessionId, queryVector.length);

            // 2. Search Qdrant with discipline filter
            List<QdrantSearchResult> searchResults = qdrantService.search(queryVector, topK > 0 ? topK : retrieveTopK, discipline);
            logger.info("RAG retrieve qdrant hits: sessionId={}, hitCount={}, topVector={}",
                    sessionId, searchResults.size(), buildQdrantHitSample(searchResults, 5));

            // 3. Rerank
            int currentYear = Year.now().getValue();
            List<RagEvidenceItem> items = new ArrayList<>();
            for (QdrantSearchResult sr : searchResults) {
                RagEvidenceItem item = new RagEvidenceItem();
                item.setChunkUid(sr.getChunkUid());
                item.setDocUid(getStringPayload(sr, "docUid"));
                item.setPaperId(getStringPayload(sr, "paperId"));
                item.setTitle(getStringPayload(sr, "title"));
                item.setContent(getStringPayload(sr, "content"));
                item.setUrl(getStringPayload(sr, "url"));
                item.setChunkType(getStringPayload(sr, "chunkType"));
                item.setVectorScore(sr.getScore());

                int paperYear = getIntPayload(sr, "year", currentYear);
                int citationCount = getIntPayload(sr, "citationCount", 0);
                item.setYear(paperYear);

                // Rerank formula:
                // finalScore = 0.70 * vectorSimilarity
                //            + 0.15 * min(citationCount, 100) / 100
                //            + 0.15 * max(0, 1.0 - (currentYear - paperYear) / 20)
                double vectorSim = sr.getScore();
                double citationScore = Math.min(citationCount, 100) / 100.0;
                double recencyScore = Math.max(0, 1.0 - (currentYear - paperYear) / 20.0);
                double finalScore = 0.70 * vectorSim + 0.15 * citationScore + 0.15 * recencyScore;
                item.setRerankScore(finalScore);

                items.add(item);
            }

            // 4. Sort by rerank score descending, take top-N
            items.sort(Comparator.comparingDouble(RagEvidenceItem::getRerankScore).reversed());
            int limit = Math.min(rerankTopN, items.size());
            List<RagEvidenceItem> finalItems = items.subList(0, limit);
            logger.info("RAG rerank done: sessionId={}, inputCount={}, outputCount={}, topRerank={}",
                    sessionId, items.size(), finalItems.size(), buildRerankSample(finalItems, 5));
            return finalItems;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("RAG retrieve and rerank failed", e);
            throw new BusinessException(ErrorCodes.RAG_RETRIEVE_FAILED, "RAG检索失败: " + e.getMessage());
        }
    }

    public RagStatsResult getStats() {
        long totalPoints = qdrantService.getPointCount();
        long totalDocs = corpusDocRepository.count();
        long totalChunks = corpusChunkRepository.count();
        String collectionName = qdrantService.getCollectionName();
        String status = qdrantService.getCollectionStatus();
        return new RagStatsResult(totalPoints, totalDocs, totalChunks, collectionName, status);
    }

    @Transactional
    public void reindex(Long sessionId) {
        // Delete existing corpus data for this session
        List<PaperCorpusDocEntity> existingDocs = corpusDocRepository.findBySessionId(sessionId);
        if (!existingDocs.isEmpty()) {
            List<String> docUids = existingDocs.stream().map(PaperCorpusDocEntity::getDocUid).toList();
            List<Long> docIds = existingDocs.stream().map(PaperCorpusDocEntity::getId).toList();
            qdrantService.deleteByDocUids(docUids);
            corpusChunkRepository.deleteByDocIdIn(docIds);
            corpusDocRepository.deleteBySessionId(sessionId);
        }

        // Re-index from paper sources
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (!sources.isEmpty()) {
            String discipline = null;
            indexSources(sessionId, sources, discipline);
        }
    }

    private String getStringPayload(QdrantSearchResult sr, String key) {
        Object val = sr.getPayload().get(key);
        return val == null ? "" : val.toString();
    }

    private int getIntPayload(QdrantSearchResult sr, String key, int defaultValue) {
        Object val = sr.getPayload().get(key);
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(val.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String buildSourceSample(List<PaperSourceEntity> sources, int limit) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(limit, sources.size());
        for (int i = 0; i < max; i++) {
            PaperSourceEntity s = sources.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(i + 1)
                    .append(":paperId=").append(s.getPaperId())
                    .append(",title=").append(truncate(s.getTitle(), 48))
                    .append(",year=").append(s.getYear());
        }
        return sb.toString();
    }

    private String buildQdrantHitSample(List<QdrantSearchResult> hits, int limit) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(limit, hits.size());
        for (int i = 0; i < max; i++) {
            QdrantSearchResult hit = hits.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(i + 1)
                    .append(":chunkUid=").append(hit.getChunkUid())
                    .append(",score=").append(String.format("%.4f", hit.getScore()))
                    .append(",title=").append(truncate(getStringPayload(hit, "title"), 48))
                    .append(",paperId=").append(truncate(getStringPayload(hit, "paperId"), 36));
        }
        return sb.toString();
    }

    private String buildRerankSample(List<RagEvidenceItem> items, int limit) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(limit, items.size());
        for (int i = 0; i < max; i++) {
            RagEvidenceItem item = items.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(i + 1)
                    .append(":chunkUid=").append(item.getChunkUid())
                    .append(",vector=").append(String.format("%.4f", item.getVectorScore()))
                    .append(",rerank=").append(String.format("%.4f", item.getRerankScore()))
                    .append(",title=").append(truncate(item.getTitle(), 48))
                    .append(",paperId=").append(truncate(item.getPaperId(), 36));
        }
        return sb.toString();
    }

    private String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen) + "...";
    }
}
