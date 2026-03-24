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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineExpandStepBatchTest {
    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;

    @Test
    void execute_shouldUseBatchExpandWhenEnabled() throws Exception {
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                new ObjectMapper(),
                true,
                2,
                1,
                8
        );

        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-batch");
        task.setProjectId("project-batch");
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(1L);
        session.setTaskId("task-batch");
        session.setTopic("topic");
        session.setTopicRefined("refined");
        session.setDiscipline("cs");
        session.setDegreeLevel("master");
        session.setResearchQuestionsJson("[\"Q1\"]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(1L);
        version.setVersionNo(1);
        version.setOutlineJson("""
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]},
                  {"title":"C2","summary":"S2","sections":[{"title":"2.1"}]}
                ]}
                """);

        when(paperTopicSessionRepository.findByTaskId("task-batch")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(1L)).thenReturn(Optional.of(version));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(new PaperSourceEntity()));
        when(modelService.expandPaperOutlineBatch(
                eq("task-batch"), eq("project-batch"), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-2"), eq(2)
        )).thenReturn(new ObjectMapper().readTree("""
                {
                  "chapters":[
                    {"index":1,"title":"C1","summary":"S1","objective":"O1","sections":[{"title":"1.1","content":"正文1","coreArgument":"论点1","method":"","dataPlan":"","expectedResult":"","citations":[]}]},
                    {"index":2,"title":"C2","summary":"S2","objective":"O2","sections":[{"title":"2.1","content":"正文2","coreArgument":"论点2","method":"","dataPlan":"","expectedResult":"","citations":[]}]}
                  ],
                  "citationMap":[]
                }
                """));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        assertTrue(cap.getValue().getManuscriptJson().contains("正文1"));
        assertTrue(cap.getValue().getManuscriptJson().contains("正文2"));
    }
}
