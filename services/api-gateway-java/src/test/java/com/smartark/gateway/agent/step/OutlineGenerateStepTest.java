package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineGenerateStepTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;

    @Test
    void execute_generatesOutlineAndPersistsNextVersion() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineGenerateStep step = new OutlineGenerateStep(
                modelService,
                paperTopicSessionRepository,
                paperSourceRepository,
                paperOutlineVersionRepository,
                objectMapper
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-x");
        task.setProjectId("project-x");

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(7L);
        session.setTopic("原始主题");
        session.setTopicRefined("细化主题");
        session.setDiscipline("软件工程");
        session.setDegreeLevel("本科");
        session.setMethodPreference("实验法");
        session.setResearchQuestionsJson("[\"问题1\"]");

        PaperSourceEntity source = new PaperSourceEntity();
        source.setSessionId(7L);
        source.setPaperId("pid-1");
        source.setTitle("paper title");

        PaperOutlineVersionEntity oldVersion = new PaperOutlineVersionEntity();
        oldVersion.setVersionNo(2);

        JsonNode generatedOutline = objectMapper.readTree("{\"chapters\":[{\"title\":\"第一章\",\"sections\":[{\"title\":\"1.1\"}]}],\"references\":[]}");

        when(paperTopicSessionRepository.findByTaskId("task-x")).thenReturn(Optional.of(session));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(source));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(7L)).thenReturn(Optional.of(oldVersion));
        when(modelService.generatePaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(generatedOutline);

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> versionCaptor = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(versionCaptor.capture());
        PaperOutlineVersionEntity savedVersion = versionCaptor.getValue();
        assertEquals(3, savedVersion.getVersionNo());
        assertEquals("GB/T 7714", savedVersion.getCitationStyle());
        assertTrue(savedVersion.getOutlineJson().contains("chapters"));

        ArgumentCaptor<PaperTopicSessionEntity> sessionCaptor = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(sessionCaptor.capture());
        assertEquals("outlined", sessionCaptor.getValue().getStatus());
        assertTrue(context.getOutlineDraftJson().contains("第一章"));
    }
}
