package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.dto.TopicAdoptRequest;
import com.smartark.gateway.dto.TopicSuggestRequest;
import com.smartark.gateway.dto.TopicSuggestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicSuggestionServiceTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository topicSessionRepository;

    private TopicSuggestionService topicSuggestionService;

    @BeforeEach
    void setUp() {
        topicSuggestionService = new TopicSuggestionService(modelService, topicSessionRepository, new ObjectMapper());
    }

    @Test
    void suggest_createsNewSessionAndReturnsSuggestions() {
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> {
            PaperTopicSessionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });
        when(modelService.chat(eq("topic_suggestion"), any())).thenReturn("""
                [
                  {
                    "title":"T1",
                    "researchQuestions":["Q1","Q2"],
                    "rationale":"R1",
                    "keywords":["k1","k2"]
                  }
                ]
                """);

        TopicSuggestResult result = topicSuggestionService.suggest(
                new TopicSuggestRequest(null, "AI in education", "undergraduate", null),
                "7"
        );

        assertEquals(1L, result.sessionId());
        assertEquals(1, result.round());
        assertEquals(1, result.suggestions().size());
        assertEquals("T1", result.suggestions().get(0).title());
    }

    @Test
    void suggest_reusesExistingSession() {
        PaperTopicSessionEntity existing = new PaperTopicSessionEntity();
        existing.setId(10L);
        existing.setUserId(7L);
        existing.setTaskId("task-10");
        existing.setProjectId("project-10");
        existing.setTopic("existing topic");
        existing.setDiscipline("cs");
        existing.setDegreeLevel("undergraduate");
        existing.setStatus("suggesting");
        existing.setResearchQuestionsJson("[]");
        existing.setSuggestionRound(1);
        existing.setCreatedAt(LocalDateTime.now().minusDays(1));
        existing.setUpdatedAt(LocalDateTime.now().minusDays(1));

        when(topicSessionRepository.findByIdAndUserId(10L, 7L)).thenReturn(Optional.of(existing));
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelService.chat(eq("topic_suggestion"), any())).thenReturn("""
                [{"title":"T2","researchQuestions":["Q1"],"rationale":"R2","keywords":["k1"]}]
                """);

        TopicSuggestResult result = topicSuggestionService.suggest(
                new TopicSuggestRequest(10L, "AI in education", null, null),
                "7"
        );

        assertEquals(10L, result.sessionId());
        assertEquals(2, result.round());
    }

    @Test
    void suggest_incrementsRound() {
        PaperTopicSessionEntity existing = buildSession(11L, 7L);
        existing.setSuggestionRound(2);
        when(topicSessionRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(existing));
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelService.chat(eq("topic_suggestion"), any())).thenReturn("""
                [{"title":"T","researchQuestions":["Q"],"rationale":"R","keywords":["k"]}]
                """);

        TopicSuggestResult result = topicSuggestionService.suggest(new TopicSuggestRequest(11L, "dir", null, null), "7");
        assertEquals(3, result.round());
    }

    @Test
    void suggest_passesPreviousSuggestionsToPrompt() {
        PaperTopicSessionEntity existing = buildSession(12L, 7L);
        existing.setSuggestedTopicsJson("[{\"title\":\"old\"}]");
        when(topicSessionRepository.findByIdAndUserId(12L, 7L)).thenReturn(Optional.of(existing));
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelService.chat(eq("topic_suggestion"), any())).thenReturn("""
                [{"title":"T","researchQuestions":["Q"],"rationale":"R","keywords":["k"]}]
                """);

        topicSuggestionService.suggest(new TopicSuggestRequest(12L, "dir", "c", null), "7");

        ArgumentCaptor<java.util.Map<String, String>> varsCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(modelService).chat(eq("topic_suggestion"), varsCaptor.capture());
        assertEquals("[{\"title\":\"old\"}]", varsCaptor.getValue().get("previousSuggestions"));
    }

    @Test
    void adopt_setsTopicFromSelectedSuggestion() {
        PaperTopicSessionEntity existing = buildSession(20L, 7L);
        existing.setSuggestedTopicsJson("""
                [
                  {"title":"T1","researchQuestions":["Q1"],"rationale":"R1","keywords":["k1"]},
                  {"title":"T2","researchQuestions":["Q2","Q3"],"rationale":"R2","keywords":["k2"]}
                ]
                """);
        when(topicSessionRepository.findByIdAndUserId(20L, 7L)).thenReturn(Optional.of(existing));
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperTopicSessionEntity saved = topicSuggestionService.adopt(new TopicAdoptRequest(20L, 1, null, null), "7");
        assertEquals("T2", saved.getTopic());
        assertTrue(saved.getResearchQuestionsJson().contains("Q2"));
        assertEquals("adopted", saved.getStatus());
    }

    @Test
    void adopt_usesCustomTitleWhenProvided() {
        PaperTopicSessionEntity existing = buildSession(21L, 7L);
        existing.setSuggestedTopicsJson("[{\"title\":\"T1\",\"researchQuestions\":[\"Q1\"],\"rationale\":\"R\",\"keywords\":[\"k\"]}]");
        when(topicSessionRepository.findByIdAndUserId(21L, 7L)).thenReturn(Optional.of(existing));
        when(topicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperTopicSessionEntity saved = topicSuggestionService.adopt(
                new TopicAdoptRequest(21L, 0, "Custom Title", List.of("Custom Q")),
                "7"
        );
        assertEquals("Custom Title", saved.getTopic());
        assertTrue(saved.getResearchQuestionsJson().contains("Custom Q"));
    }

    @Test
    void adopt_throwsOnInvalidIndex() {
        PaperTopicSessionEntity existing = buildSession(22L, 7L);
        existing.setSuggestedTopicsJson("[{\"title\":\"T1\",\"researchQuestions\":[\"Q1\"],\"rationale\":\"R\",\"keywords\":[\"k\"]}]");
        when(topicSessionRepository.findByIdAndUserId(22L, 7L)).thenReturn(Optional.of(existing));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> topicSuggestionService.adopt(new TopicAdoptRequest(22L, 3, null, null), "7"));
        assertEquals(ErrorCodes.VALIDATION_FAILED, ex.getCode());
    }

    private PaperTopicSessionEntity buildSession(Long id, Long userId) {
        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(id);
        session.setUserId(userId);
        session.setTaskId("task-" + id);
        session.setProjectId("project-" + id);
        session.setTopic("topic");
        session.setDiscipline("cs");
        session.setDegreeLevel("undergraduate");
        session.setStatus("suggested");
        session.setResearchQuestionsJson("[]");
        session.setSuggestionRound(0);
        session.setCreatedAt(LocalDateTime.now().minusHours(1));
        session.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return session;
    }
}
