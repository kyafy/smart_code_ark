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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AcademicRetrieveStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(AcademicRetrieveStep.class);
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

        String baseQuery = (session.getTopicRefined() == null || session.getTopicRefined().isBlank())
                ? session.getTopic()
                : session.getTopicRefined();
        String globalQuery = baseQuery + " " + session.getDiscipline();
        List<PaperSourceItem> globalSources = semanticScholarService.searchPapers(globalQuery, 12);

        List<String> questions = parseQuestions(session.getResearchQuestionsJson());
        Map<String, List<PaperSourceItem>> sectionSources = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            String sectionKey = "rq_" + (i + 1);
            String question = questions.get(i);
            String scopedQuery = baseQuery + " " + session.getDiscipline() + " " + question;
            List<PaperSourceItem> scoped = semanticScholarService.searchPapers(scopedQuery, 5);
            sectionSources.put(sectionKey, scoped);
        }

        Map<String, PaperSourceItem> merged = new LinkedHashMap<>();
        for (PaperSourceItem item : globalSources) {
            if (item.getPaperId() != null && !item.getPaperId().isBlank()) {
                merged.putIfAbsent(item.getPaperId(), item);
            }
        }
        for (List<PaperSourceItem> scoped : sectionSources.values()) {
            for (PaperSourceItem item : scoped) {
                if (item.getPaperId() != null && !item.getPaperId().isBlank()) {
                    merged.putIfAbsent(item.getPaperId(), item);
                }
            }
        }

        if (merged.isEmpty()) {
            List<PaperSourceItem> fallback = semanticScholarService.searchPapers(baseQuery, 5);
            for (PaperSourceItem item : fallback) {
                if (item.getPaperId() != null && !item.getPaperId().isBlank()) {
                    merged.putIfAbsent(item.getPaperId(), item);
                }
            }
        }
        context.setRetrievedSources(new ArrayList<>(merged.values()));

        paperSourceRepository.deleteBySessionId(session.getId());
        LocalDateTime now = LocalDateTime.now();
        for (PaperSourceItem source : globalSources) {
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
        for (Map.Entry<String, List<PaperSourceItem>> entry : sectionSources.entrySet()) {
            String sectionKey = entry.getKey();
            for (PaperSourceItem source : entry.getValue()) {
                PaperSourceEntity entity = new PaperSourceEntity();
                entity.setSessionId(session.getId());
                entity.setSectionKey(sectionKey);
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
        }
        if (globalSources.isEmpty() && sectionSources.values().stream().allMatch(List::isEmpty)) {
            logger.warn("retrieval_empty: taskId={}, sessionId={}, query={}", context.getTask().getId(), session.getId(), baseQuery);
            PaperSourceEntity degraded = new PaperSourceEntity();
            degraded.setSessionId(session.getId());
            degraded.setSectionKey("degraded");
            degraded.setPaperId("NO_RESULT_" + session.getId());
            degraded.setTitle("No retrieval result");
            degraded.setAuthorsJson("[]");
            degraded.setCreatedAt(now);
            paperSourceRepository.save(degraded);
        }

        session.setStatus("retrieved");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private List<String> parseQuestions(String researchQuestionsJson) {
        if (researchQuestionsJson == null || researchQuestionsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> questions = new ArrayList<>();
            var node = objectMapper.readTree(researchQuestionsJson);
            if (node.isArray()) {
                node.forEach(item -> {
                    String q = item.asText("");
                    if (!q.isBlank()) {
                        questions.add(q);
                    }
                });
            }
            return questions;
        } catch (Exception e) {
            return List.of();
        }
    }
}
