package com.smartark.gateway.agent.step;

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
class QualityRewriteStepRuntimeTest {

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
        QualityRewriteStep step = new QualityRewriteStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                objectMapper,
                75,
                1,
                runtimeGraphClient,
                true
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-rewrite");
        task.setProjectId("project-runtime-rewrite");
        task.setUserId(19L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTaskLogger((level, content) -> {
        });

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(300L);
        session.setTaskId("task-runtime-rewrite");
        session.setTopic("Runtime topic");
        session.setTopicRefined("Runtime topic refined");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(300L);
        version.setVersionNo(1);
        version.setCitationStyle("GB/T 7714");
        version.setRewriteRound(0);
        version.setOutlineJson("""
                {
                  "chapters":[
                    {"title":"Chapter 1","sections":[{"title":"1.1"}]}
                  ]
                }
                """);
        version.setManuscriptJson("""
                {
                  "chapters":[
                    {
                      "title":"Chapter 1",
                      "sections":[
                        {"title":"1.1","content":"old content","coreArgument":"old argument","citations":[]}
                      ]
                    }
                  ]
                }
                """);
        version.setQualityReportJson("""
                {
                  "overallScore": 62,
                  "issues": ["Need stronger analysis"]
                }
                """);

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-rewrite-1",
                "task-runtime-rewrite",
                "paper",
                "completed",
                Map.of(
                        "rewrite_json",
                        objectMapper.readTree("""
                                {
                                  "manuscript": {
                                    "chapters": [
                                      {
                                        "title": "Chapter 1",
                                        "sections": [
                                          {
                                            "title": "1.1",
                                            "content": "runtime rewritten content",
                                            "coreArgument": "runtime rewritten argument",
                                            "citations": []
                                          }
                                        ]
                                      }
                                    ]
                                  },
                                  "appliedIssues": ["Need stronger analysis"],
                                  "summary": "runtime rewrite done"
                                }
                                """)
                )
        );

        when(paperTopicSessionRepository.findByTaskId("task-runtime-rewrite")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(300L)).thenReturn(Optional.of(version));
        when(runtimeGraphClient.runPaperGraph(eq("task-runtime-rewrite"), eq("project-runtime-rewrite"), eq(19L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runPaperGraph(eq("task-runtime-rewrite"), eq("project-runtime-rewrite"), eq(19L), any(Map.class));
        verify(modelService, never()).rewriteOutlineByQualityIssues(any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<PaperOutlineVersionEntity> versionCaptor = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(versionCaptor.capture());
        assertTrue(versionCaptor.getValue().getManuscriptJson().contains("runtime rewritten content"));
        assertEquals(1, versionCaptor.getValue().getRewriteRound());
        assertEquals(0, java.math.BigDecimal.valueOf(62).compareTo(versionCaptor.getValue().getQualityScore()));
    }
}
