package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineExpandStepTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;

    @Test
    void execute_generatesManuscriptJsonAndUpdateStatus() throws Exception {
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );
        TaskEntity task = new TaskEntity();
        task.setId("task-expand");
        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(10L);
        session.setTaskId("task-expand");
        session.setTopic("原主题");
        session.setTopicRefined("细化主题");
        session.setResearchQuestionsJson("[\"问题1\"]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(10L);
        version.setVersionNo(1);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"第一章\",\"sections\":[{\"title\":\"1.1\"}]}]}");

        when(paperTopicSessionRepository.findByTaskId("task-expand")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(10L)).thenReturn(Optional.of(version));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(10L)).thenReturn(java.util.List.of(new PaperSourceEntity()));
        when(modelService.expandPaperOutline(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(
                new ObjectMapper().readTree("{\"chapters\":[{\"title\":\"第一章\",\"sections\":[]}]}"),
                new ObjectMapper().readTree("{\"chapters\":[{\"title\":\"第一章\",\"sections\":[{\"title\":\"1.1\",\"coreArgument\":\"A\",\"method\":\"M\",\"dataPlan\":\"D\",\"expectedResult\":\"E\",\"citations\":[\"c1\"]}]}]}")
        );

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> vCap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(vCap.capture());
        assertTrue(vCap.getValue().getManuscriptJson().contains("chapters"));
        assertTrue(context.getManuscriptJson().contains("第一章"));
        verify(modelService, times(2)).expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<PaperTopicSessionEntity> sCap = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(sCap.capture());
        assertEquals("expanded", sCap.getValue().getStatus());
    }
}
