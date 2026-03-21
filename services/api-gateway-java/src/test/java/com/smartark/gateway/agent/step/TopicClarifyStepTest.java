package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicClarifyStepTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;

    @Test
    void execute_createsSessionAndWritesContextPaperSessionId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TopicClarifyStep step = new TopicClarifyStep(modelService, objectMapper, paperTopicSessionRepository);

        TaskEntity task = new TaskEntity();
        task.setId("task-abc");
        task.setProjectId("project-1");
        task.setUserId(12L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTopic("多智能体协作");
        context.setDiscipline("软件工程");
        context.setDegreeLevel("本科");
        context.setMethodPreference("实验法");

        when(modelService.clarifyPaperTopic(any(), any(), any(), any(), any(), any()))
                .thenReturn(objectMapper.readTree("{\"topicRefined\":\"多智能体协作系统设计\",\"researchQuestions\":[\"Q1\",\"Q2\"]}"));
        when(paperTopicSessionRepository.findByTaskId("task-abc")).thenReturn(Optional.empty());
        when(paperTopicSessionRepository.save(any(PaperTopicSessionEntity.class))).thenAnswer(invocation -> {
            PaperTopicSessionEntity saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        step.execute(context);

        ArgumentCaptor<PaperTopicSessionEntity> captor = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(captor.capture());
        PaperTopicSessionEntity entity = captor.getValue();
        assertEquals("task-abc", entity.getTaskId());
        assertEquals("project-1", entity.getProjectId());
        assertEquals(12L, entity.getUserId());
        assertEquals("clarified", entity.getStatus());
        assertEquals("多智能体协作系统设计", entity.getTopicRefined());
        assertEquals("[\"Q1\",\"Q2\"]", entity.getResearchQuestionsJson());
        assertNotNull(entity.getCreatedAt());
        assertNotNull(entity.getUpdatedAt());
        assertEquals(100L, context.getPaperSessionId());
    }
}
