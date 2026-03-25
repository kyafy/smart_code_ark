package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OutlineQualityCheckStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(OutlineQualityCheckStep.class);
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;
    private final LangchainRuntimeGraphClient runtimeGraphClient;
    private final boolean runtimePaperGraphEnabled;

    public OutlineQualityCheckStep(ModelService modelService,
                                   PaperTopicSessionRepository paperTopicSessionRepository,
                                   PaperOutlineVersionRepository paperOutlineVersionRepository,
                                   ObjectMapper objectMapper) {
        this(modelService, paperTopicSessionRepository, paperOutlineVersionRepository, objectMapper, null, false);
    }

    @Autowired
    public OutlineQualityCheckStep(ModelService modelService,
                                   PaperTopicSessionRepository paperTopicSessionRepository,
                                   PaperOutlineVersionRepository paperOutlineVersionRepository,
                                   ObjectMapper objectMapper,
                                   LangchainRuntimeGraphClient runtimeGraphClient,
                                   @Value("${smartark.langchain.runtime.paper-graph-enabled:false}") boolean runtimePaperGraphEnabled) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimePaperGraphEnabled = runtimePaperGraphEnabled;
    }

    @Override
    public String getStepCode() {
        return "outline_quality_check";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow();

        // Build RAG evidence JSON if available
        JsonNode ragEvidenceNode = null;
        List<RagEvidenceItem> ragItems = context.getRagEvidenceItems();
        if (ragItems != null && !ragItems.isEmpty()) {
            ragEvidenceNode = objectMapper.readTree(objectMapper.writeValueAsString(ragItems));
        }

        String chapterEvidenceMapJson = context.getChapterEvidenceMapJson();
        if (chapterEvidenceMapJson == null || chapterEvidenceMapJson.isBlank()) {
            chapterEvidenceMapJson = version.getChapterEvidenceMapJson();
        }

        JsonNode outlineJson = objectMapper.readTree(version.getOutlineJson());
        JsonNode qualityReport = qualityCheckWithRuntimeFallback(
                context,
                session,
                version,
                outlineJson,
                ragEvidenceNode,
                chapterEvidenceMapJson
        );
        version.setQualityReportJson(objectMapper.writeValueAsString(qualityReport));
        int score = 0;
        if (qualityReport.has("overallScore")) {
            score = qualityReport.path("overallScore").asInt(0);
        } else if (qualityReport.has("score")) {
            score = qualityReport.path("score").asInt(0);
        }
        if (score > 0) {
            version.setQualityScore(BigDecimal.valueOf(score));
        }
        paperOutlineVersionRepository.save(version);

        context.setQualityReportJson(version.getQualityReportJson());
        session.setStatus("checked");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private JsonNode qualityCheckWithRuntimeFallback(AgentExecutionContext context,
                                                     PaperTopicSessionEntity session,
                                                     PaperOutlineVersionEntity version,
                                                     JsonNode outlineJson,
                                                     JsonNode ragEvidenceNode,
                                                     String chapterEvidenceMapJson) {
        JsonNode runtimeReport = qualityCheckFromRuntime(context, session, version, outlineJson, ragEvidenceNode, chapterEvidenceMapJson);
        if (runtimeReport != null) {
            context.logInfo("Outline quality check routed to langchain runtime graph successfully.");
            return runtimeReport;
        }
        return modelService.qualityCheckPaperOutline(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                version.getCitationStyle(),
                outlineJson,
                ragEvidenceNode,
                chapterEvidenceMapJson
        );
    }

    private JsonNode qualityCheckFromRuntime(AgentExecutionContext context,
                                             PaperTopicSessionEntity session,
                                             PaperOutlineVersionEntity version,
                                             JsonNode outlineJson,
                                             JsonNode ragEvidenceNode,
                                             String chapterEvidenceMapJson) {
        if (!runtimePaperGraphEnabled || runtimeGraphClient == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "outline_quality_check");
            input.put("topic", defaultString(session.getTopic()));
            input.put("topicRefined", defaultString(session.getTopicRefined()));
            input.put("citationStyle", defaultString(version.getCitationStyle()));
            input.put("outline", objectMapper.convertValue(outlineJson, Object.class));
            input.put("ragEvidence", ragEvidenceNode == null ? List.of() : objectMapper.convertValue(ragEvidenceNode, Object.class));
            input.put("chapterEvidenceMap", chapterEvidenceMapJson == null ? "" : chapterEvidenceMapJson);

            LangchainGraphRunResult result = runtimeGraphClient.runPaperGraph(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    context.getTask().getUserId(),
                    input
            );
            JsonNode resultNode = objectMapper.valueToTree(result == null ? Map.of() : result.result());
            JsonNode normalized = normalizeRuntimeQualityReport(resultNode);
            if (normalized == null || !normalized.isObject()) {
                logger.warn("Runtime graph returned invalid quality report shape, fallback to model-service: taskId={}, sessionId={}",
                        context.getTask().getId(), session.getId());
                return null;
            }
            logger.info("Outline quality check generated by runtime graph: taskId={}, sessionId={}",
                    context.getTask().getId(), session.getId());
            return normalized;
        } catch (Exception e) {
            logger.warn("Runtime graph quality check failed, fallback to model-service: taskId={}, sessionId={}, error={}",
                    context.getTask().getId(), session.getId(), e.getMessage());
            return null;
        }
    }

    private JsonNode normalizeRuntimeQualityReport(JsonNode resultNode) {
        if (resultNode == null || resultNode.isNull()) {
            return null;
        }
        JsonNode qualityReport = resultNode.path("quality_report_json");
        if (!qualityReport.isObject()) {
            qualityReport = resultNode.path("qualityReport");
        }
        if (!qualityReport.isObject()) {
            qualityReport = resultNode.path("quality_report");
        }
        if (!qualityReport.isObject()) {
            if (resultNode.isObject() && (resultNode.has("overallScore") || resultNode.has("score"))) {
                qualityReport = resultNode;
            } else {
                return null;
            }
        }
        return ensureQualityReportFields(qualityReport);
    }

    private JsonNode ensureQualityReportFields(JsonNode raw) {
        var root = objectMapper.createObjectNode();
        int score = raw.path("overallScore").asInt(raw.path("score").asInt(0));
        int evidenceCoverage = raw.path("evidenceCoverage").asInt(Math.max(0, Math.min(100, score)));
        root.put("logicClosedLoop", raw.path("logicClosedLoop").asBoolean(score >= 70));
        root.put("methodConsistency", defaultString(raw.path("methodConsistency").asText("ok")));
        root.put("citationVerifiability", defaultString(raw.path("citationVerifiability").asText("ok")));
        root.put("overallScore", Math.max(0, Math.min(100, score)));
        root.put("evidenceCoverage", Math.max(0, Math.min(100, evidenceCoverage)));
        if (raw.path("issues").isArray()) {
            root.set("issues", raw.path("issues"));
        } else {
            root.set("issues", objectMapper.createArrayNode());
        }
        if (raw.path("uncoveredSections").isArray()) {
            root.set("uncoveredSections", raw.path("uncoveredSections"));
        } else {
            root.set("uncoveredSections", objectMapper.createArrayNode());
        }
        return root;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
