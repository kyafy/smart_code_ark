package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class QualityRewriteStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(QualityRewriteStep.class);
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;
    private final int scoreThreshold;
    private final int maxRewriteRounds;
    private final LangchainRuntimeGraphClient runtimeGraphClient;
    private final boolean runtimePaperGraphEnabled;

    public QualityRewriteStep(ModelService modelService,
                              PaperTopicSessionRepository paperTopicSessionRepository,
                              PaperOutlineVersionRepository paperOutlineVersionRepository,
                              ObjectMapper objectMapper,
                              @Value("${smartark.paper.quality-rewrite.score-threshold:75}") int scoreThreshold,
                              @Value("${smartark.paper.quality-rewrite.max-rounds:1}") int maxRewriteRounds) {
        this(modelService, paperTopicSessionRepository, paperOutlineVersionRepository, objectMapper,
                scoreThreshold, maxRewriteRounds, null, false);
    }

    @Autowired
    public QualityRewriteStep(ModelService modelService,
                              PaperTopicSessionRepository paperTopicSessionRepository,
                              PaperOutlineVersionRepository paperOutlineVersionRepository,
                              ObjectMapper objectMapper,
                              @Value("${smartark.paper.quality-rewrite.score-threshold:75}") int scoreThreshold,
                              @Value("${smartark.paper.quality-rewrite.max-rounds:1}") int maxRewriteRounds,
                              LangchainRuntimeGraphClient runtimeGraphClient,
                              @Value("${smartark.langchain.runtime.paper-graph-enabled:false}") boolean runtimePaperGraphEnabled) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
        this.scoreThreshold = scoreThreshold;
        this.maxRewriteRounds = maxRewriteRounds;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimePaperGraphEnabled = runtimePaperGraphEnabled;
    }

    @Override
    public String getStepCode() {
        return "quality_rewrite";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow();

        ObjectNode qualityRoot = parseObject(version.getQualityReportJson());
        int score = readScore(qualityRoot);
        List<String> issues = readIssues(qualityRoot.path("issues"));
        ObjectNode stableManuscript = parseObject(version.getManuscriptJson());
        StructureExpectation structureExpectation = buildStructureExpectation(parseObject(version.getOutlineJson()));
        validateStructureExpectation(structureExpectation, context.getTask().getId());
        StructureCoverage beforeCoverage = evaluateStructureCoverage(stableManuscript.path("chapters"), structureExpectation);

        int rewriteRound = version.getRewriteRound() == null ? 0 : version.getRewriteRound();
        boolean shouldRewrite = score > 0 && score < scoreThreshold && rewriteRound < maxRewriteRounds;
        if (shouldRewrite) {
            JsonNode rewritten = executeQualityRewrite(context, session, version, qualityRoot, stableManuscript);
            JsonNode manuscript = rewritten.path("manuscript");
            if (!manuscript.isMissingNode() && !manuscript.isNull()) {
                StructureCoverage afterCoverage = evaluateStructureCoverage(manuscript.path("chapters"), structureExpectation);
                boolean structureRegression = isStructureRegression(beforeCoverage, afterCoverage);
                boolean allowWrite = isPreCommitPass(afterCoverage, structureExpectation) && !structureRegression;
                if (allowWrite) {
                    version.setManuscriptJson(objectMapper.writeValueAsString(manuscript));
                    logGuardProbe(context, "precommit", beforeCoverage, afterCoverage, "allow_write", "none", null, null);
                } else {
                    String errorCode = structureRegression
                            ? "PAPER_PRECOMMIT_REWRITE_REGRESSION"
                            : "PAPER_PRECOMMIT_COVERAGE_FAILED";
                    String errorMessage = structureRegression
                            ? "quality_rewrite 写回前复检未通过: rewrite 结构回退"
                            : "quality_rewrite 写回前复检未通过: coverage 不达标";
                    logGuardProbe(context, "precommit", beforeCoverage, afterCoverage, "block_write",
                            "keep_previous_stable", errorCode, errorMessage);
                }
            }
            JsonNode appliedIssues = rewritten.path("appliedIssues");
            if (appliedIssues.isArray()) {
                issues = readIssues(appliedIssues);
            }
            version.setRewriteRound(rewriteRound + 1);
        }

        if (score > 0) {
            version.setQualityScore(BigDecimal.valueOf(score));
        }
        paperOutlineVersionRepository.save(version);

        context.setQualityReportJson(version.getQualityReportJson());
        context.setQualityIssues(issues);
        context.setManuscriptJson(version.getManuscriptJson());
        session.setStatus("checked");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private JsonNode executeQualityRewrite(AgentExecutionContext context,
                                           PaperTopicSessionEntity session,
                                           PaperOutlineVersionEntity version,
                                           ObjectNode qualityRoot,
                                           ObjectNode stableManuscript) {
        JsonNode runtimeRewrite = executeRuntimeQualityRewrite(context, session, version, qualityRoot, stableManuscript);
        if (runtimeRewrite != null) {
            context.logInfo("Quality rewrite routed to langchain runtime graph successfully.");
            return runtimeRewrite;
        }
        return modelService.rewriteOutlineByQualityIssues(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                version.getCitationStyle(),
                qualityRoot,
                stableManuscript
        );
    }

    private JsonNode executeRuntimeQualityRewrite(AgentExecutionContext context,
                                                  PaperTopicSessionEntity session,
                                                  PaperOutlineVersionEntity version,
                                                  ObjectNode qualityRoot,
                                                  ObjectNode stableManuscript) {
        if (!runtimePaperGraphEnabled || runtimeGraphClient == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "quality_rewrite");
            input.put("topic", defaultString(session.getTopic()));
            input.put("topicRefined", defaultString(session.getTopicRefined()));
            input.put("citationStyle", defaultString(version.getCitationStyle()));
            input.put("qualityReport", objectMapper.convertValue(qualityRoot, Object.class));
            input.put("stableManuscript", objectMapper.convertValue(stableManuscript, Object.class));
            input.put("rewriteRound", version.getRewriteRound() == null ? 0 : version.getRewriteRound());

            LangchainGraphRunResult result = runtimeGraphClient.runPaperGraph(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    context.getTask().getUserId(),
                    input
            );
            JsonNode resultNode = objectMapper.valueToTree(result == null ? Map.of() : result.result());
            JsonNode normalized = normalizeRuntimeRewriteResult(resultNode, qualityRoot, stableManuscript);
            if (normalized == null || normalized.path("manuscript").isMissingNode()
                    || normalized.path("manuscript").isNull()
                    || !normalized.path("manuscript").path("chapters").isArray()) {
                logger.warn("Runtime graph returned invalid rewrite shape, fallback to model-service: taskId={}, sessionId={}",
                        context.getTask().getId(), session.getId());
                return null;
            }
            logger.info("Quality rewrite generated by runtime graph: taskId={}, sessionId={}",
                    context.getTask().getId(), session.getId());
            return normalized;
        } catch (Exception e) {
            logger.warn("Runtime graph quality rewrite failed, fallback to model-service: taskId={}, sessionId={}, error={}",
                    context.getTask().getId(), session.getId(), e.getMessage());
            return null;
        }
    }

    private JsonNode normalizeRuntimeRewriteResult(JsonNode resultNode, ObjectNode qualityRoot, ObjectNode stableManuscript) {
        if (resultNode == null || resultNode.isNull()) {
            return null;
        }
        JsonNode rewriteJson = resultNode.path("rewrite_json");
        if (rewriteJson.isObject() && rewriteJson.path("manuscript").isObject()) {
            return ensureRuntimeRewriteFields((ObjectNode) rewriteJson, qualityRoot, stableManuscript);
        }

        JsonNode manuscriptJson = resultNode.path("manuscript_json");
        if (manuscriptJson.isObject()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("manuscript", manuscriptJson);
            return ensureRuntimeRewriteFields(wrapper, qualityRoot, stableManuscript);
        }

        JsonNode manuscript = resultNode.path("manuscript");
        if (manuscript.isObject()) {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("manuscript", manuscript);
            return ensureRuntimeRewriteFields(wrapper, qualityRoot, stableManuscript);
        }

        JsonNode chapters = resultNode.path("chapters");
        if (chapters.isArray()) {
            ObjectNode manuscriptWrapper = objectMapper.createObjectNode();
            manuscriptWrapper.set("chapters", chapters);
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.set("manuscript", manuscriptWrapper);
            return ensureRuntimeRewriteFields(wrapper, qualityRoot, stableManuscript);
        }
        return null;
    }

    private ObjectNode ensureRuntimeRewriteFields(ObjectNode rewriteRoot, ObjectNode qualityRoot, ObjectNode stableManuscript) {
        ObjectNode root = rewriteRoot == null ? objectMapper.createObjectNode() : rewriteRoot.deepCopy();
        JsonNode manuscriptNode = root.path("manuscript");
        if (!manuscriptNode.isObject() || !manuscriptNode.path("chapters").isArray()) {
            root.set("manuscript", stableManuscript);
        }
        if (!root.path("appliedIssues").isArray()) {
            JsonNode issues = qualityRoot == null ? objectMapper.createArrayNode() : qualityRoot.path("issues");
            root.set("appliedIssues", issues.isArray() ? issues : objectMapper.createArrayNode());
        }
        if (!root.path("summary").isTextual()) {
            root.put("summary", "runtime_quality_rewrite");
        }
        return root;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private ObjectNode parseObject(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                return (ObjectNode) node;
            }
            return objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private int readScore(JsonNode qualityRoot) {
        if (qualityRoot.has("overallScore")) {
            return qualityRoot.path("overallScore").asInt(0);
        }
        if (qualityRoot.has("score")) {
            return qualityRoot.path("score").asInt(0);
        }
        return 0;
    }

    private List<String> readIssues(JsonNode issuesNode) {
        List<String> issues = new ArrayList<>();
        if (!issuesNode.isArray()) {
            return issues;
        }
        issuesNode.forEach(n -> {
            String issue;
            if (n.isObject()) {
                issue = n.path("message").asText("");
                if (issue.isBlank()) {
                    issue = n.path("suggestion").asText("");
                }
                if (issue.isBlank()) {
                    issue = n.path("field").asText("");
                }
            } else {
                issue = n.asText("");
            }
            if (!issue.isBlank()) {
                issues.add(issue);
            }
        });
        return issues;
    }

    private StructureExpectation buildStructureExpectation(ObjectNode outlineRoot) {
        JsonNode chapters = outlineRoot.path("chapters");
        if (!chapters.isArray()) {
            return new StructureExpectation(0, new LinkedHashMap<>(), 0);
        }
        Map<Integer, Integer> expectedSections = new LinkedHashMap<>();
        int chapterIndex = 0;
        int totalSections = 0;
        for (JsonNode chapter : chapters) {
            int chapterSections = 0;
            JsonNode sections = chapter.path("sections");
            if (sections.isArray()) {
                for (JsonNode section : sections) {
                    JsonNode subsections = section.path("subsections");
                    if (subsections.isArray() && !subsections.isEmpty()) {
                        chapterSections += subsections.size();
                    } else {
                        chapterSections += 1;
                    }
                }
            }
            expectedSections.put(chapterIndex, chapterSections);
            totalSections += chapterSections;
            chapterIndex++;
        }
        return new StructureExpectation(chapters.size(), expectedSections, totalSections);
    }

    private void validateStructureExpectation(StructureExpectation expectation, String taskId) {
        boolean invalid = expectation.expectedChapterCount() <= 0
                || expectation.expectedSectionCountTotal() <= 0
                || expectation.expectedSectionCountPerChapter().size() != expectation.expectedChapterCount()
                || expectation.expectedSectionCountPerChapter().values().stream().anyMatch(count -> count == null || count <= 0);
        if (!invalid) {
            return;
        }
        String message = "quality_rewrite 生成前约束输入无效";
        logger.warn("paper_guard taskId={}, stepCode=quality_rewrite, guardStage=precheck, decision=block_write, fallbackAction=task_failed, errorCode=PAPER_PRECHECK_INVALID_INPUT, message={}",
                taskId, message);
        throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, message);
    }

    private StructureCoverage evaluateStructureCoverage(JsonNode manuscriptChapters, StructureExpectation expectation) {
        int generatedChapterCount = manuscriptChapters != null && manuscriptChapters.isArray() ? manuscriptChapters.size() : 0;
        int generatedSectionCount = 0;
        int matchedSectionCount = 0;
        if (manuscriptChapters != null && manuscriptChapters.isArray()) {
            int chapterIndex = 0;
            for (JsonNode chapter : manuscriptChapters) {
                JsonNode sections = chapter.path("sections");
                int actualSections = sections.isArray() ? sections.size() : 0;
                generatedSectionCount += actualSections;
                Integer expectedSections = expectation.expectedSectionCountPerChapter().get(chapterIndex);
                if (expectedSections != null && expectedSections > 0) {
                    matchedSectionCount += Math.min(actualSections, expectedSections);
                }
                chapterIndex++;
            }
        }
        double chapterCoverage = expectation.expectedChapterCount() <= 0
                ? 0D
                : (double) generatedChapterCount / (double) expectation.expectedChapterCount();
        double sectionCoverage = expectation.expectedSectionCountTotal() <= 0
                ? 0D
                : (double) matchedSectionCount / (double) expectation.expectedSectionCountTotal();
        return new StructureCoverage(generatedChapterCount, generatedSectionCount, matchedSectionCount, chapterCoverage, sectionCoverage);
    }

    private boolean isPreCommitPass(StructureCoverage coverage, StructureExpectation expectation) {
        return coverage.generatedChapterCount() == expectation.expectedChapterCount()
                && coverage.matchedSectionCount() == expectation.expectedSectionCountTotal()
                && coverage.chapterCoverage() >= 1.0D
                && coverage.sectionCoverage() >= 1.0D;
    }

    private boolean isStructureRegression(StructureCoverage before, StructureCoverage after) {
        final double epsilon = 1e-9;
        if (after.generatedChapterCount() < before.generatedChapterCount()) {
            return true;
        }
        if (after.generatedSectionCount() < before.generatedSectionCount()) {
            return true;
        }
        if (after.chapterCoverage() + epsilon < before.chapterCoverage()) {
            return true;
        }
        return after.sectionCoverage() + epsilon < before.sectionCoverage();
    }

    private void logGuardProbe(AgentExecutionContext context,
                               String guardStage,
                               StructureCoverage beforeCoverage,
                               StructureCoverage afterCoverage,
                               String decision,
                               String fallbackAction,
                               String errorCode,
                               String errorMessage) {
        logger.info("paper_guard taskId={}, stepCode=quality_rewrite, guardStage={}, chapterCoverageBefore={}, sectionCoverageBefore={}, chapterCoverageAfter={}, sectionCoverageAfter={}, decision={}, fallbackAction={}, errorCode={}, errorMessage={}",
                context.getTask().getId(),
                guardStage,
                formatCoverage(beforeCoverage.chapterCoverage()),
                formatCoverage(beforeCoverage.sectionCoverage()),
                formatCoverage(afterCoverage.chapterCoverage()),
                formatCoverage(afterCoverage.sectionCoverage()),
                decision,
                fallbackAction,
                errorCode == null ? "none" : errorCode,
                errorMessage == null ? "" : errorMessage);
        context.logInfo("paper_guard taskId=" + context.getTask().getId()
                + ", stepCode=quality_rewrite"
                + ", guardStage=" + guardStage
                + ", chapterCoverageBefore=" + formatCoverage(beforeCoverage.chapterCoverage())
                + ", sectionCoverageBefore=" + formatCoverage(beforeCoverage.sectionCoverage())
                + ", chapterCoverageAfter=" + formatCoverage(afterCoverage.chapterCoverage())
                + ", sectionCoverageAfter=" + formatCoverage(afterCoverage.sectionCoverage())
                + ", decision=" + decision
                + ", fallbackAction=" + fallbackAction
                + ", errorCode=" + (errorCode == null ? "none" : errorCode));
    }

    private String formatCoverage(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record StructureExpectation(int expectedChapterCount,
                                        Map<Integer, Integer> expectedSectionCountPerChapter,
                                        int expectedSectionCountTotal) {
    }

    private record StructureCoverage(int generatedChapterCount,
                                     int generatedSectionCount,
                                     int matchedSectionCount,
                                     double chapterCoverage,
                                     double sectionCoverage) {
    }
}
