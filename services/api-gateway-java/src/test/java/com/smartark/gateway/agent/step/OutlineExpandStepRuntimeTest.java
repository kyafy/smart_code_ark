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
class OutlineExpandStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @Test
    void execute_usesRuntimeGraphWhenEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                objectMapper,
                runtimeGraphClient,
                true,
                2,
                1,
                8,
                true
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-expand-runtime");
        task.setProjectId("project-expand-runtime");
        task.setUserId(7L);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setTaskLogger((level, content) -> {
        });

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(201L);
        session.setTaskId("task-expand-runtime");
        session.setTopic("Runtime Topic");
        session.setTopicRefined("Runtime Topic Refined");
        session.setDiscipline("cs");
        session.setDegreeLevel("master");
        session.setMethodPreference("experiment");
        session.setResearchQuestionsJson("[\"RQ1\"]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(201L);
        version.setVersionNo(2);
        version.setOutlineJson("""
                {
                  "chapters":[
                    {
                      "title":"Chapter 1",
                      "summary":"S1",
                      "sections":[{"title":"1.1 Problem"}]
                    }
                  ]
                }
                """);

        PaperSourceEntity source = new PaperSourceEntity();
        source.setSessionId(201L);
        source.setPaperId("paper-1");
        source.setSectionKey("normal");

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-expand-1",
                "task-expand-runtime",
                "paper",
                "completed",
                Map.of(
                        "expanded_json",
                        objectMapper.readTree("""
                                {
                                  "chapters":[
                                    {
                                      "index":1,
                                      "title":"Chapter 1",
                                      "summary":"S1",
                                      "objective":"O1",
                                      "sections":[
                                        {
                                          "title":"1.1 Problem",
                                          "content":"This section contains runtime generated analysis.",
                                          "coreArgument":"Runtime core argument.",
                                          "method":"",
                                          "dataPlan":"",
                                          "expectedResult":"",
                                          "citations":[]
                                        }
                                      ]
                                    }
                                  ],
                                  "citationMap":[]
                                }
                                """)
                )
        );

        when(paperTopicSessionRepository.findByTaskId("task-expand-runtime")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(201L)).thenReturn(Optional.of(version));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(201L)).thenReturn(List.of(source));
        when(runtimeGraphClient.runPaperGraph(eq("task-expand-runtime"), eq("project-expand-runtime"), eq(7L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runPaperGraph(eq("task-expand-runtime"), eq("project-expand-runtime"), eq(7L), any(Map.class));
        verify(modelService, never()).expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(paperOutlineVersionRepository).save(any(PaperOutlineVersionEntity.class));
        assertTrue(context.getManuscriptJson().contains("runtime generated analysis"));
    }
}
