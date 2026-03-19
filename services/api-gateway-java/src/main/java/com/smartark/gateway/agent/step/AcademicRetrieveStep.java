package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.PaperSourceItem;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.SemanticScholarService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class AcademicRetrieveStep implements AgentStep {
    private final SemanticScholarService semanticScholarService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final ObjectMapper objectMapper;

    public AcademicRetrieveStep(SemanticScholarService semanticScholarService,
                                PaperTopicSessionRepository paperTopicSessionRepository,
                                PaperSourceRepository paperSourceRepository,
                                ObjectMapper objectMapper) {
        this.semanticScholarService = semanticScholarService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "academic_retrieve";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        context.setPaperSessionId(session.getId());

        String query = (session.getTopicRefined() == null || session.getTopicRefined().isBlank())
                ? session.getTopic()
                : session.getTopicRefined();
        List<PaperSourceItem> sources = semanticScholarService.searchPapers(query + " " + session.getDiscipline(), 20);
        context.setRetrievedSources(sources);

        paperSourceRepository.deleteBySessionId(session.getId());
        LocalDateTime now = LocalDateTime.now();
        for (PaperSourceItem source : sources) {
            PaperSourceEntity entity = new PaperSourceEntity();
            entity.setSessionId(session.getId());
            entity.setSectionKey("global");
            entity.setPaperId(source.getPaperId());
            entity.setTitle(source.getTitle());
            entity.setAuthorsJson(objectMapper.writeValueAsString(source.getAuthors()));
            entity.setYear(source.getYear());
            entity.setVenue(source.getVenue());
            entity.setUrl(source.getUrl());
            entity.setAbstractText(source.getAbstractText());
            entity.setEvidenceSnippet(source.getEvidenceSnippet());
            entity.setRelevanceScore(source.getRelevanceScore());
            entity.setCreatedAt(now);
            paperSourceRepository.save(entity);
        }

        session.setStatus("retrieved");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }
}
