package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
<<<<<<< HEAD
=======
import com.smartark.gateway.agent.model.RagEvidenceItem;
>>>>>>> origin/master
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
<<<<<<< HEAD
=======
import java.util.List;
>>>>>>> origin/master

@Component
public class OutlineQualityCheckStep implements AgentStep {
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;

    public OutlineQualityCheckStep(ModelService modelService,
                                   PaperTopicSessionRepository paperTopicSessionRepository,
                                   PaperOutlineVersionRepository paperOutlineVersionRepository,
                                   ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
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

<<<<<<< HEAD
=======
        // Build RAG evidence JSON if available
        JsonNode ragEvidenceNode = null;
        List<RagEvidenceItem> ragItems = context.getRagEvidenceItems();
        if (ragItems != null && !ragItems.isEmpty()) {
            ragEvidenceNode = objectMapper.readTree(objectMapper.writeValueAsString(ragItems));
        }

        String chapterEvidenceMapJson = context.getChapterEvidenceMapJson();

>>>>>>> origin/master
        JsonNode qualityReport = modelService.qualityCheckPaperOutline(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                version.getCitationStyle(),
<<<<<<< HEAD
                objectMapper.readTree(version.getOutlineJson())
=======
                objectMapper.readTree(version.getOutlineJson()),
                ragEvidenceNode,
                chapterEvidenceMapJson
>>>>>>> origin/master
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
}
