package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.config.RagProperties;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RagRetrieveRerankStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(RagRetrieveRerankStep.class);
    private final RagService ragService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final ObjectMapper objectMapper;

    private final RagProperties ragProperties;

    public RagRetrieveRerankStep(RagService ragService,
                                  PaperTopicSessionRepository paperTopicSessionRepository,
                                  ObjectMapper objectMapper,
                                  RagProperties ragProperties) {
        this.ragService = ragService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
    }

    @Override
    public String getStepCode() {
        return "rag_retrieve_rerank";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();

        // Build query from topicRefined + researchQuestions
        StringBuilder queryBuilder = new StringBuilder();
        if (session.getTopicRefined() != null && !session.getTopicRefined().isBlank()) {
            queryBuilder.append(session.getTopicRefined());
        } else {
            queryBuilder.append(session.getTopic());
        }

        if (session.getResearchQuestionsJson() != null && !session.getResearchQuestionsJson().isBlank()) {
            try {
                List<String> questions = objectMapper.readValue(session.getResearchQuestionsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                for (String q : questions) {
                    queryBuilder.append(" ").append(q);
                }
            } catch (Exception ignored) {
                // If parsing fails, just use topic
            }
        }
        String retrievalQuery = queryBuilder.toString();
        int retrieveTopK = ragProperties.getRetrieveTopK();
        logger.info("Step rag_retrieve_rerank start: taskId={}, sessionId={}, retrieveTopK={}, query={}",
                context.getTask().getId(), session.getId(), retrieveTopK, truncate(retrievalQuery, 220));

        List<RagEvidenceItem> results = ragService.retrieveAndRerank(
                session.getId(),
                retrievalQuery,
                session.getDiscipline(),
                retrieveTopK
        );
        logger.info("Step rag_retrieve_rerank output: taskId={}, sessionId={}, evidenceCount={}, topEvidence={}",
                context.getTask().getId(), session.getId(), results.size(), buildEvidenceSample(results, 5));

        context.setRagEvidenceItems(results);

        session.setStatus("rag_retrieved");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private String buildEvidenceSample(List<RagEvidenceItem> items, int limit) {
        StringBuilder sb = new StringBuilder();
        int max = Math.min(limit, items.size());
        for (int i = 0; i < max; i++) {
            RagEvidenceItem item = items.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(i + 1)
                    .append(":chunkUid=").append(item.getChunkUid())
                    .append(",rerank=").append(String.format("%.4f", item.getRerankScore()))
                    .append(",vector=").append(String.format("%.4f", item.getVectorScore()))
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
