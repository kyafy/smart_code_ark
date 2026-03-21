package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RagRetrieveRerankStep implements AgentStep {
    private final RagService ragService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${smartark.rag.retrieve-top-k:30}")
    private int retrieveTopK;

    public RagRetrieveRerankStep(RagService ragService,
                                  PaperTopicSessionRepository paperTopicSessionRepository,
                                  ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.objectMapper = objectMapper;
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

        List<RagEvidenceItem> results = ragService.retrieveAndRerank(
                session.getId(),
                queryBuilder.toString(),
                session.getDiscipline(),
                retrieveTopK
        );

        context.setRagEvidenceItems(results);

        session.setStatus("rag_retrieved");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }
}
