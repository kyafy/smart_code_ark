package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class OutlineGenerateStep implements AgentStep {
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;

    public OutlineGenerateStep(ModelService modelService,
                               PaperTopicSessionRepository paperTopicSessionRepository,
                               PaperSourceRepository paperSourceRepository,
                               PaperOutlineVersionRepository paperOutlineVersionRepository,
                               ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "outline_generate";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        JsonNode sourceNode = objectMapper.readTree(objectMapper.writeValueAsString(sources));

        JsonNode outline = modelService.generatePaperOutline(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                session.getDiscipline(),
                session.getDegreeLevel(),
                session.getMethodPreference(),
                session.getResearchQuestionsJson(),
                sourceNode
        );

        int nextVersion = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(session.getId());
        version.setVersionNo(nextVersion);
        version.setCitationStyle("GB/T 7714");
        version.setOutlineJson(objectMapper.writeValueAsString(outline));
        version.setRewriteRound(0);
        version.setCreatedAt(LocalDateTime.now());
        paperOutlineVersionRepository.save(version);

        context.setOutlineDraftJson(objectMapper.writeValueAsString(outline));
        session.setStatus("outlined");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }
}
