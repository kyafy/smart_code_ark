package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.PaperSourceItem;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ArxivService;
import com.smartark.gateway.service.CrossrefService;
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
    private final CrossrefService crossrefService;
    private final ArxivService arxivService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final ObjectMapper objectMapper;

    public AcademicRetrieveStep(SemanticScholarService semanticScholarService,
                                CrossrefService crossrefService,
                                ArxivService arxivService,
                                PaperTopicSessionRepository paperTopicSessionRepository,
                                PaperSourceRepository paperSourceRepository,
                                ObjectMapper objectMapper) {
        this.semanticScholarService = semanticScholarService;
        this.crossrefService = crossrefService;
        this.arxivService = arxivService;
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

        // --- Multi-source global retrieval ---
        List<PaperSourceItem> ssSources = semanticScholarService.searchPapers(globalQuery, 12);
        List<PaperSourceItem> crSources = crossrefService.searchPapers(globalQuery, 10);
        List<PaperSourceItem> arxivSources = List.of();
        if (ArxivService.supportsDiscipline(session.getDiscipline())) {
            arxivSources = arxivService.searchPapers(globalQuery, 10);
        }

        // Merge global sources with title-based dedup (priority: SS > Crossref > arXiv)
        List<PaperSourceItem> globalSources = mergeByTitle(ssSources, crSources, arxivSources);
        logger.info("multi_source_retrieve: ss={}, crossref={}, arxiv={}, merged={}",
                ssSources.size(), crSources.size(), arxivSources.size(), globalSources.size());

        // --- Per-question scoped retrieval (Semantic Scholar only, unchanged) ---
        List<String> questions = parseQuestions(session.getResearchQuestionsJson());
        Map<String, List<PaperSourceItem>> sectionSources = new LinkedHashMap<>();
        for (int i = 0; i < questions.size(); i++) {
            String sectionKey = "rq_" + (i + 1);
            String question = questions.get(i);
            String scopedQuery = baseQuery + " " + session.getDiscipline() + " " + question;
            List<PaperSourceItem> scoped = semanticScholarService.searchPapers(scopedQuery, 5);
            sectionSources.put(sectionKey, scoped);
        }

        // --- Final merge for context (dedup by normalized title) ---
        Map<String, PaperSourceItem> merged = new LinkedHashMap<>();
        for (PaperSourceItem item : globalSources) {
            String key = normalizeTitle(item.getTitle());
            if (!key.isBlank()) {
                merged.putIfAbsent(key, item);
            }
        }
        for (List<PaperSourceItem> scoped : sectionSources.values()) {
            for (PaperSourceItem item : scoped) {
                String key = normalizeTitle(item.getTitle());
                if (!key.isBlank()) {
                    merged.putIfAbsent(key, item);
                }
            }
        }

        if (merged.isEmpty()) {
            List<PaperSourceItem> fallback = semanticScholarService.searchPapers(baseQuery, 5);
            for (PaperSourceItem item : fallback) {
                String key = normalizeTitle(item.getTitle());
                if (!key.isBlank()) {
                    merged.putIfAbsent(key, item);
                }
            }
        }
        context.setRetrievedSources(new ArrayList<>(merged.values()));

        // --- Persist to DB ---
        paperSourceRepository.deleteBySessionId(session.getId());
        LocalDateTime now = LocalDateTime.now();
        for (PaperSourceItem source : globalSources) {
            persistSource(session.getId(), "global", source, now);
        }
        for (Map.Entry<String, List<PaperSourceItem>> entry : sectionSources.entrySet()) {
            String sectionKey = entry.getKey();
            for (PaperSourceItem source : entry.getValue()) {
                persistSource(session.getId(), sectionKey, source, now);
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

    private void persistSource(Long sessionId, String sectionKey, PaperSourceItem source, LocalDateTime now) {
        try {
            PaperSourceEntity entity = new PaperSourceEntity();
            entity.setSessionId(sessionId);
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
            entity.setSource(source.getSource());
            entity.setCreatedAt(now);
            paperSourceRepository.save(entity);
        } catch (Exception e) {
            logger.warn("persist_source_failed: paperId={}, error={}", source.getPaperId(), e.getMessage());
        }
    }

    /**
     * Merge results from multiple sources, deduplicating by normalized title.
     * Priority: earlier lists take precedence (first-in wins).
     */
    @SafeVarargs
    private List<PaperSourceItem> mergeByTitle(List<PaperSourceItem>... sources) {
        Map<String, PaperSourceItem> merged = new LinkedHashMap<>();
        for (List<PaperSourceItem> sourceList : sources) {
            for (PaperSourceItem item : sourceList) {
                String key = normalizeTitle(item.getTitle());
                if (!key.isBlank()) {
                    merged.putIfAbsent(key, item);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private String normalizeTitle(String title) {
        if (title == null) return "";
        return title.toLowerCase().replaceAll("\\s+", "").replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
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
