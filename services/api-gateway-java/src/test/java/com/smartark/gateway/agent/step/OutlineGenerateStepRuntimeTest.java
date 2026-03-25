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
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineGenerateStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @Test
    void execute_usesRuntimeGraphWhenEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineGenerateStep step = new OutlineGenerateStep(
                modelService,
                paperTopicSessionRepository,
                paperSourceRepository,
                paperOutlineVersionRepository,
                objectMapper,
                12000,
                20,
                15,
                6,
                4,
                runtimeGraphClient,
                true
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-runtime");
        task.setProjectId("project-runtime");
        task.setUserId(11L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTaskLogger((level, content) -> {
        });

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(33L);
        session.setTopic("运行时论文主题");
        session.setTopicRefined("运行时论文细化主题");
        session.setDiscipline("软件工程");
        session.setDegreeLevel("本科");
        session.setMethodPreference("实验法");
        session.setResearchQuestionsJson("[\"问题1\"]");

        PaperSourceEntity source = new PaperSourceEntity();
        source.setSessionId(33L);
        source.setPaperId("pid-runtime");
        source.setTitle("runtime paper");

        PaperOutlineVersionEntity oldVersion = new PaperOutlineVersionEntity();
        oldVersion.setVersionNo(1);

        JsonNode outlineJson = objectMapper.readTree("""
                {
                  "topic": "运行时论文主题",
                  "topicRefined": "运行时论文细化主题",
                  "researchQuestions": ["问题1"],
                  "chapters": [
                    {
                      "title": "引言",
                      "sections": [{"title": "研究背景"}]
                    }
                  ]
                }
                """);

        LangchainGraphRunResult graphResult = new LangchainGraphRunResult(
                "run-1",
                "task-runtime",
                "paper",
                "completed",
                Map.of("outline_json", objectMapper.convertValue(outlineJson, Object.class))
        );

        when(paperTopicSessionRepository.findByTaskId("task-runtime")).thenReturn(Optional.of(session));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(33L)).thenReturn(List.of(source));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(33L)).thenReturn(Optional.of(oldVersion));
        when(runtimeGraphClient.runPaperGraph(eq("task-runtime"), eq("project-runtime"), eq(11L), any(Map.class)))
                .thenReturn(graphResult);

        step.execute(context);

        verify(runtimeGraphClient).runPaperGraph(eq("task-runtime"), eq("project-runtime"), eq(11L), any(Map.class));
        verify(modelService, never()).generatePaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(paperOutlineVersionRepository).save(any(PaperOutlineVersionEntity.class));
        assertTrue(context.getOutlineDraftJson().contains("引言"));
    }
}
