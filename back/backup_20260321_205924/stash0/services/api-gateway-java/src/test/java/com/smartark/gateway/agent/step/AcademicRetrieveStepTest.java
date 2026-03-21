package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.PaperSourceItem;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.SemanticScholarService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcademicRetrieveStepTest {

    @Mock
    private SemanticScholarService semanticScholarService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;

    @Test
    void execute_retrievesAndPersistsSources_thenUpdateSessionStatus() throws Exception {
        AcademicRetrieveStep step = new AcademicRetrieveStep(
                semanticScholarService,
                paperTopicSessionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-1");

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(55L);
        session.setTaskId("task-1");
        session.setTopic("原始主题");
        session.setTopicRefined("细化主题");
        session.setDiscipline("计算机科学");
        session.setResearchQuestionsJson("[\"RQ1\"]");
        session.setStatus("clarified");

        PaperSourceItem item = new PaperSourceItem();
        item.setPaperId("p-1");
        item.setTitle("A Study on Agents");
        item.setAuthors(List.of("Alice", "Bob"));
        item.setYear(2024);
        item.setVenue("AAAI");
        item.setUrl("https://example.org/p-1");
        item.setAbstractText("abstract");
        item.setEvidenceSnippet("snippet");
        item.setRelevanceScore(BigDecimal.ONE);

        when(paperTopicSessionRepository.findByTaskId("task-1")).thenReturn(Optional.of(session));
        when(semanticScholarService.searchPapers(eq("细化主题 计算机科学"), eq(12))).thenReturn(List.of(item));
        when(semanticScholarService.searchPapers(eq("细化主题 计算机科学 RQ1"), eq(5))).thenReturn(List.of(item));

        step.execute(context);

        verify(paperSourceRepository).deleteBySessionId(55L);
        ArgumentCaptor<PaperSourceEntity> sourceCaptor = ArgumentCaptor.forClass(PaperSourceEntity.class);
        verify(paperSourceRepository, atLeastOnce()).save(sourceCaptor.capture());
        var savedAll = sourceCaptor.getAllValues();
        assertTrue(savedAll.stream().anyMatch(s -> "global".equals(s.getSectionKey())));
        assertTrue(savedAll.stream().anyMatch(s -> "rq_1".equals(s.getSectionKey())));
        PaperSourceEntity saved = savedAll.get(0);
        assertEquals(55L, saved.getSessionId());
        assertEquals("p-1", saved.getPaperId());
        assertEquals("A Study on Agents", saved.getTitle());
        assertEquals("[\"Alice\",\"Bob\"]", saved.getAuthorsJson());
        assertEquals("AAAI", saved.getVenue());
        assertNotNull(saved.getCreatedAt());

        ArgumentCaptor<PaperTopicSessionEntity> sessionCaptor = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(sessionCaptor.capture());
        assertEquals("retrieved", sessionCaptor.getValue().getStatus());
        assertEquals(55L, context.getPaperSessionId());
        assertEquals(1, context.getRetrievedSources().size());
    }

    @Test
    void execute_writeDegradedMarkerWhenNoRetrievalResult() throws Exception {
        AcademicRetrieveStep step = new AcademicRetrieveStep(
                semanticScholarService,
                paperTopicSessionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-empty");

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(77L);
        session.setTaskId("task-empty");
        session.setTopic("空检索主题");
        session.setTopicRefined("空检索细化主题");
        session.setDiscipline("计算机科学");
        session.setResearchQuestionsJson("[\"RQ1\"]");
        session.setStatus("clarified");

        when(paperTopicSessionRepository.findByTaskId("task-empty")).thenReturn(Optional.of(session));
        when(semanticScholarService.searchPapers(eq("空检索细化主题 计算机科学"), eq(12))).thenReturn(List.of());
        when(semanticScholarService.searchPapers(eq("空检索细化主题 计算机科学 RQ1"), eq(5))).thenReturn(List.of());
        when(semanticScholarService.searchPapers(eq("空检索细化主题"), eq(5))).thenReturn(List.of());

        step.execute(context);

        ArgumentCaptor<PaperSourceEntity> sourceCaptor = ArgumentCaptor.forClass(PaperSourceEntity.class);
        verify(paperSourceRepository, atLeastOnce()).save(sourceCaptor.capture());
        assertTrue(sourceCaptor.getAllValues().stream().anyMatch(s -> "degraded".equals(s.getSectionKey())));
        assertEquals(0, context.getRetrievedSources().size());
    }
}
