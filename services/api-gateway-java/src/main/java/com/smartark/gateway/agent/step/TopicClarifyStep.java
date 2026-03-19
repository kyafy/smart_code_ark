package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TopicClarifyStep implements AgentStep {
    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final PaperTopicSessionRepository paperTopicSessionRepository;

    public TopicClarifyStep(ModelService modelService,
                            ObjectMapper objectMapper,
                            PaperTopicSessionRepository paperTopicSessionRepository) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
    }

    @Override
    public String getStepCode() {
        return "topic_clarify";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        JsonNode clarified = modelService.clarifyPaperTopic(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                context.getTopic(),
                context.getDiscipline(),
                context.getDegreeLevel(),
                context.getMethodPreference()
        );

        String topicRefined = clarified.path("topicRefined").asText(context.getTopic());
        JsonNode researchQuestions = clarified.path("researchQuestions");

        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseGet(PaperTopicSessionEntity::new);
        session.setTaskId(context.getTask().getId());
        session.setProjectId(context.getTask().getProjectId());
        session.setUserId(context.getTask().getUserId());
        session.setTopic(context.getTopic());
        session.setDiscipline(context.getDiscipline());
        session.setDegreeLevel(context.getDegreeLevel());
        session.setMethodPreference(context.getMethodPreference());
        session.setStatus("clarified");
        session.setTopicRefined(topicRefined);
        session.setResearchQuestionsJson(objectMapper.writeValueAsString(researchQuestions));
        if (session.getCreatedAt() == null) {
            session.setCreatedAt(LocalDateTime.now());
        }
        session.setUpdatedAt(LocalDateTime.now());
        session = paperTopicSessionRepository.save(session);

        context.setPaperSessionId(session.getId());
    }
}
