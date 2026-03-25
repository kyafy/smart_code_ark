package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodegenBackendStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private TemplateRepoService templateRepoService;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @TempDir
    Path tempDir;

    @Test
    void execute_usesRuntimeCodegenGraphWhenEnabled() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(
                modelService,
                new ObjectMapper(),
                templateRepoService,
                runtimeGraphClient,
                true
        );
        AgentExecutionContext context = buildContext();

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-backend-1",
                "task-runtime-backend",
                "codegen",
                "completed",
                Map.of(
                        "codegen_files",
                        List.of(
                                Map.of(
                                        "path", "backend/src/main/java/com/example/App.java",
                                        "content", "package com.example;\npublic class App {}"
                                )
                        )
                )
        );

        when(runtimeGraphClient.runCodegenGraph(eq("task-runtime-backend"), eq("project-runtime-backend"), eq(9L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-runtime-backend"), eq("project-runtime-backend"), eq(9L), any(Map.class));
        verify(modelService, never()).generateFileContent(any(), any(), any(), any(), any(), any(), any());
        assertThat(Files.readString(tempDir.resolve("backend/src/main/java/com/example/App.java")))
                .contains("class App");
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-backend");
        task.setProjectId("project-runtime-backend");
        task.setUserId(9L);
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"backend test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        FilePlanItem backendItem = new FilePlanItem();
        backendItem.setPath("backend/src/main/java/com/example/App.java");
        backendItem.setGroup("backend");
        backendItem.setPriority(10);
        context.setFilePlan(List.of(backendItem));
        return context;
    }
}
