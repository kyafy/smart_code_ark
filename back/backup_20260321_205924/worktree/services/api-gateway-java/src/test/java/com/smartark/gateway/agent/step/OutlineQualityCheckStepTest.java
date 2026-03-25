package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineQualityCheckStepTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;

    @Test
    void execute_runsQualityCheckAndUpdatesVersionAndSession() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineQualityCheckStep step = new OutlineQualityCheckStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                objectMapper
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-q");
        task.setProjectId("project-q");

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(22L);
        session.setTaskId("task-q");
        session.setTopic("原始题目");
        session.setTopicRefined("细化题目");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(22L);
        version.setVersionNo(1);
        version.setCitationStyle("GB/T 7714");
        version.setOutlineJson("{\"chapters\":[{\"title\":\"第一章\"}]}");

        JsonNode quality = objectMapper.readTree("{\"score\":86,\"issues\":[\"结构可优化\"]}");

        when(paperTopicSessionRepository.findByTaskId("task-q")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(22L)).thenReturn(Optional.of(version));
<<<<<<< HEAD
        when(modelService.qualityCheckPaperOutline(any(), any(), any(), any(), any(), any())).thenReturn(quality);
=======
        when(modelService.qualityCheckPaperOutline(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(quality);
>>>>>>> origin/master

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> versionCaptor = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(versionCaptor.capture());
        assertTrue(versionCaptor.getValue().getQualityReportJson().contains("\"score\":86"));
        assertTrue(context.getQualityReportJson().contains("\"issues\""));

        ArgumentCaptor<PaperTopicSessionEntity> sessionCaptor = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(sessionCaptor.capture());
        assertEquals("checked", sessionCaptor.getValue().getStatus());
    }
}
