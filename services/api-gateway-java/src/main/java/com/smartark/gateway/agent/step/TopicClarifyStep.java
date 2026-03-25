package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class TopicClarifyStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(TopicClarifyStep.class);
    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final LangchainRuntimeGraphClient runtimeGraphClient;
    private final boolean runtimePaperGraphEnabled;

    public TopicClarifyStep(ModelService modelService,
                            ObjectMapper objectMapper,
                            PaperTopicSessionRepository paperTopicSessionRepository) {
        this(modelService, objectMapper, paperTopicSessionRepository, null, false);
    }

    @Autowired
    public TopicClarifyStep(ModelService modelService,
                            ObjectMapper objectMapper,
                            PaperTopicSessionRepository paperTopicSessionRepository,
                            LangchainRuntimeGraphClient runtimeGraphClient,
                            @Value("${smartark.langchain.runtime.paper-graph-enabled:false}") boolean runtimePaperGraphEnabled) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimePaperGraphEnabled = runtimePaperGraphEnabled;
    }

    @Override
    public String getStepCode() {
        return "topic_clarify";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        JsonNode clarified = clarifyTopicWithRuntimeFallback(context);

        String topicRefined = clarified.path("topicRefined").asText(context.getTopic());
        JsonNode researchQuestions = clarified.path("researchQuestions");
        if (!researchQuestions.isArray()) {
            researchQuestions = objectMapper.createArrayNode();
        }

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

    private JsonNode clarifyTopicWithRuntimeFallback(AgentExecutionContext context) {
        JsonNode runtimeClarified = clarifyTopicFromRuntime(context);
        if (runtimeClarified != null) {
            context.logInfo("Topic clarify routed to langchain runtime graph successfully.");
            return runtimeClarified;
        }
        return modelService.clarifyPaperTopic(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                context.getTopic(),
                context.getDiscipline(),
                context.getDegreeLevel(),
                context.getMethodPreference()
        );
    }

    private JsonNode clarifyTopicFromRuntime(AgentExecutionContext context) {
        if (!runtimePaperGraphEnabled || runtimeGraphClient == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "topic_clarify");
            input.put("topic", defaultString(context.getTopic()));
            input.put("discipline", defaultString(context.getDiscipline()));
            input.put("degreeLevel", defaultString(context.getDegreeLevel()));
            input.put("methodPreference", defaultString(context.getMethodPreference()));

            LangchainGraphRunResult result = runtimeGraphClient.runPaperGraph(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    context.getTask().getUserId(),
                    input
            );
            JsonNode resultNode = objectMapper.valueToTree(result == null ? Map.of() : result.result());
            JsonNode normalized = normalizeRuntimeTopicClarifyResult(resultNode, context.getTopic());
            if (normalized == null || normalized.path("topicRefined").asText("").isBlank()
                    || !normalized.path("researchQuestions").isArray()) {
                logger.warn("Runtime graph returned invalid topic clarify shape, fallback to model-service: taskId={}",
                        context.getTask().getId());
                return null;
            }
            logger.info("Topic clarify generated by runtime graph: taskId={}", context.getTask().getId());
            return normalized;
        } catch (Exception e) {
            logger.warn("Runtime graph topic clarify failed, fallback to model-service: taskId={}, error={}",
                    context.getTask().getId(), e.getMessage());
            return null;
        }
    }

    private JsonNode normalizeRuntimeTopicClarifyResult(JsonNode resultNode, String defaultTopic) {
        if (resultNode == null || resultNode.isNull()) {
            return null;
        }
        JsonNode clarifyNode = resultNode.path("topic_clarify_json");
        if (!clarifyNode.isObject()) {
            clarifyNode = resultNode.path("topicClarify");
        }
        if (!clarifyNode.isObject()) {
            clarifyNode = resultNode.path("topic_clarify");
        }
        if (!clarifyNode.isObject()) {
            clarifyNode = resultNode;
        }

        var root = objectMapper.createObjectNode();
        String topicRefined = clarifyNode.path("topicRefined").asText("");
        if (topicRefined.isBlank()) {
            topicRefined = clarifyNode.path("topic_refined").asText(defaultString(defaultTopic));
        }
        root.put("topicRefined", defaultString(topicRefined));

        JsonNode questions = clarifyNode.path("researchQuestions");
        if (!questions.isArray()) {
            questions = clarifyNode.path("research_questions");
        }
        if (questions.isArray()) {
            root.set("researchQuestions", questions);
        } else {
            root.set("researchQuestions", objectMapper.createArrayNode());
        }
        return root;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
