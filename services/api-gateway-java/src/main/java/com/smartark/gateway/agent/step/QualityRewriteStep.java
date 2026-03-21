package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class QualityRewriteStep implements AgentStep {
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;
    private final int scoreThreshold;
    private final int maxRewriteRounds;

    public QualityRewriteStep(ModelService modelService,
                              PaperTopicSessionRepository paperTopicSessionRepository,
                              PaperOutlineVersionRepository paperOutlineVersionRepository,
                              ObjectMapper objectMapper,
                              @Value("${smartark.paper.quality-rewrite.score-threshold:75}") int scoreThreshold,
                              @Value("${smartark.paper.quality-rewrite.max-rounds:1}") int maxRewriteRounds) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
        this.scoreThreshold = scoreThreshold;
        this.maxRewriteRounds = maxRewriteRounds;
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

        JsonNode qualityRoot = parseObject(version.getQualityReportJson());
        int score = readScore(qualityRoot);
        List<String> issues = readIssues(qualityRoot.path("issues"));

        int rewriteRound = version.getRewriteRound() == null ? 0 : version.getRewriteRound();
        boolean shouldRewrite = score > 0 && score < scoreThreshold && rewriteRound < maxRewriteRounds;
        if (shouldRewrite) {
            JsonNode rewritten = modelService.rewriteOutlineByQualityIssues(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    session.getTopic(),
                    session.getTopicRefined(),
                    version.getCitationStyle(),
                    qualityRoot,
                    parseObject(version.getManuscriptJson())
            );
            JsonNode manuscript = rewritten.path("manuscript");
            if (!manuscript.isMissingNode() && !manuscript.isNull()) {
                version.setManuscriptJson(objectMapper.writeValueAsString(manuscript));
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
}
