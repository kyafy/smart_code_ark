package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicClarifyStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @Test
    void execute_usesRuntimeGraphWhenEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TopicClarifyStep step = new TopicClarifyStep(
                modelService,
                objectMapper,
                paperTopicSessionRepository,
                runtimeGraphClient,
                true
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-topic-runtime");
        task.setProjectId("project-topic-runtime");
        task.setUserId(21L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTaskLogger((level, content) -> {
        });
        context.setTopic("AI coding workflow");
        context.setDiscipline("software engineering");
        context.setDegreeLevel("master");
        context.setMethodPreference("experiment");

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-topic-1",
                "task-topic-runtime",
                "paper",
                "completed",
                Map.of(
                        "topic_clarify_json",
                        objectMapper.readTree("""
                                {
                                  "topicRefined":"AI coding workflow system design",
                                  "researchQuestions":["RQ1","RQ2"]
                                }
                                """)
                )
        );

        when(runtimeGraphClient.runPaperGraph(eq("task-topic-runtime"), eq("project-topic-runtime"), eq(21L), any(Map.class)))
                .thenReturn(runtimeResult);
        when(paperTopicSessionRepository.findByTaskId("task-topic-runtime")).thenReturn(Optional.empty());
        when(paperTopicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> {
            PaperTopicSessionEntity saved = invocation.getArgument(0);
            saved.setId(501L);
            return saved;
        });

        step.execute(context);

        verify(runtimeGraphClient).runPaperGraph(eq("task-topic-runtime"), eq("project-topic-runtime"), eq(21L), any(Map.class));
        verify(modelService, never()).clarifyPaperTopic(any(), any(), any(), any(), any(), any());

        ArgumentCaptor<PaperTopicSessionEntity> captor = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(captor.capture());
        assertEquals("AI coding workflow system design", captor.getValue().getTopicRefined());
        assertEquals("[\"RQ1\",\"RQ2\"]", captor.getValue().getResearchQuestionsJson());
        assertEquals(501L, context.getPaperSessionId());
        assertTrue(captor.getValue().getStatus().equals("clarified"));
    }
}
