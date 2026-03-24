package com.smartark.gateway.agent.step;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QualityRewriteStepTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;

    @Test
    void execute_rewriteWhenScoreLowAndSetRound() throws Exception {
        QualityRewriteStep step = new QualityRewriteStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                new ObjectMapper(),
                75,
                1
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-rewrite");
        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(11L);
        session.setTaskId("task-rewrite");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(11L);
        version.setVersionNo(1);
        version.setRewriteRound(0);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\"}]}]}");
        version.setManuscriptJson("{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\",\"content\":\"原正文\",\"coreArgument\":\"原论点\",\"citations\":[]}]}]}");
        version.setQualityReportJson("{\"overallScore\":60,\"issues\":[\"缺少方法细节\"]}");

        when(paperTopicSessionRepository.findByTaskId("task-rewrite")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(11L)).thenReturn(Optional.of(version));
        when(modelService.rewriteOutlineByQualityIssues(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new ObjectMapper().readTree("{\"manuscript\":{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\",\"content\":\"改写正文\",\"coreArgument\":\"改写论点\",\"citations\":[]}]}],\"revisionNotes\":[\"ok\"]},\"appliedIssues\":[\"缺少方法细节\"],\"summary\":\"done\"}"));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> vCap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(vCap.capture());
        assertEquals(1, vCap.getValue().getRewriteRound());
        assertTrue(vCap.getValue().getManuscriptJson().contains("revisionNotes"));
        assertEquals(0, java.math.BigDecimal.valueOf(60).compareTo(vCap.getValue().getQualityScore()));

        ArgumentCaptor<PaperTopicSessionEntity> sCap = ArgumentCaptor.forClass(PaperTopicSessionEntity.class);
        verify(paperTopicSessionRepository).save(sCap.capture());
        assertEquals("checked", sCap.getValue().getStatus());
    }

    @Test
    void execute_skipRewriteWhenScoreAboveThreshold() throws Exception {
        QualityRewriteStep step = new QualityRewriteStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                new ObjectMapper(),
                75,
                1
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-no-rewrite");
        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(12L);
        session.setTaskId("task-no-rewrite");
        session.setStatus("expanded");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(12L);
        version.setVersionNo(1);
        version.setRewriteRound(0);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\"}]}]}");
        version.setManuscriptJson("{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\",\"content\":\"原正文\",\"coreArgument\":\"原论点\",\"citations\":[]}]}]}");
        version.setQualityReportJson("{\"overallScore\":88,\"issues\":[\"可优化\"]}");

        when(paperTopicSessionRepository.findByTaskId("task-no-rewrite")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(12L)).thenReturn(Optional.of(version));

        step.execute(context);

        verify(modelService, never()).rewriteOutlineByQualityIssues(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        ArgumentCaptor<PaperOutlineVersionEntity> vCap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(vCap.capture());
        assertEquals(0, vCap.getValue().getRewriteRound());
        assertEquals(0, java.math.BigDecimal.valueOf(88).compareTo(vCap.getValue().getQualityScore()));
    }

    @Test
    void execute_keepStableManuscriptWhenRewriteRegressed() throws Exception {
        QualityRewriteStep step = new QualityRewriteStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                new ObjectMapper(),
                75,
                1
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-regression-block");
        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(13L);
        session.setTaskId("task-regression-block");

        String stableManuscript = """
                {"chapters":[{"title":"Chapter 1","sections":[
                  {"title":"1.1","content":"原正文1","coreArgument":"原论点1","citations":[]},
                  {"title":"1.2","content":"原正文2","coreArgument":"原论点2","citations":[]}
                ]}]}
                """;

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(13L);
        version.setVersionNo(1);
        version.setRewriteRound(0);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\"},{\"title\":\"1.2\"}]}]}");
        version.setManuscriptJson(stableManuscript);
        version.setQualityReportJson("{\"overallScore\":65,\"issues\":[\"结构需优化\"]}");

        when(paperTopicSessionRepository.findByTaskId("task-regression-block")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(13L)).thenReturn(Optional.of(version));
        when(modelService.rewriteOutlineByQualityIssues(
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new ObjectMapper().readTree("{\"manuscript\":{\"chapters\":[{\"title\":\"Chapter 1\",\"sections\":[{\"title\":\"1.1\",\"content\":\"回写正文\",\"coreArgument\":\"回写论点\",\"citations\":[]}]}]},\"appliedIssues\":[\"结构需优化\"],\"summary\":\"done\"}"));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> vCap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(vCap.capture());
        assertEquals(1, vCap.getValue().getRewriteRound());
        assertTrue(vCap.getValue().getManuscriptJson().contains("原正文1"));
        assertFalse(vCap.getValue().getManuscriptJson().contains("回写正文"));
    }
}
