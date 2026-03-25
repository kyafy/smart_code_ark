package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
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
class OutlineQualityCheckStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @Test
    void execute_usesRuntimeGraphWhenEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineQualityCheckStep step = new OutlineQualityCheckStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                objectMapper,
                runtimeGraphClient,
                true
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-quality-runtime");
        task.setProjectId("project-quality-runtime");
        task.setUserId(88L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTaskLogger((level, content) -> {
        });

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(401L);
        session.setTaskId("task-quality-runtime");
        session.setTopic("Runtime quality topic");
        session.setTopicRefined("Runtime quality topic refined");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(401L);
        version.setVersionNo(1);
        version.setCitationStyle("GB/T 7714");
        version.setOutlineJson("""
                {
                  "chapters":[
                    {"title":"Chapter 1","sections":[{"title":"1.1"}]}
                  ]
                }
                """);

        JsonNode qualityNode = objectMapper.readTree("""
                {
                  "overallScore": 92,
                  "issues": [],
                  "evidenceCoverage": 90,
                  "logicClosedLoop": true,
                  "methodConsistency": "ok",
                  "citationVerifiability": "ok",
                  "uncoveredSections": []
                }
                """);
        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-quality-1",
                "task-quality-runtime",
                "paper",
                "completed",
                Map.of("quality_report_json", qualityNode)
        );

        when(paperTopicSessionRepository.findByTaskId("task-quality-runtime")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(401L)).thenReturn(Optional.of(version));
        when(runtimeGraphClient.runPaperGraph(eq("task-quality-runtime"), eq("project-quality-runtime"), eq(88L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runPaperGraph(eq("task-quality-runtime"), eq("project-quality-runtime"), eq(88L), any(Map.class));
        verify(modelService, never()).qualityCheckPaperOutline(any(), any(), any(), any(), any(), any(), any(), any());

        ArgumentCaptor<PaperOutlineVersionEntity> versionCaptor = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(versionCaptor.capture());
        assertTrue(versionCaptor.getValue().getQualityReportJson().contains("\"overallScore\":92"));
        assertEquals(0, java.math.BigDecimal.valueOf(92).compareTo(versionCaptor.getValue().getQualityScore()));
        assertTrue(context.getQualityReportJson().contains("\"evidenceCoverage\":90"));
    }
}
