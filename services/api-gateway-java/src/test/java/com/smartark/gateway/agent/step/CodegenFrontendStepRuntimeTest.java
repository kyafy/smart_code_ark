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
class CodegenFrontendStepRuntimeTest {

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
        CodegenFrontendStep step = new CodegenFrontendStep(
                modelService,
                new ObjectMapper(),
                templateRepoService,
                runtimeGraphClient,
                true
        );
        AgentExecutionContext context = buildContext();

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-frontend-1",
                "task-runtime-frontend",
                "codegen",
                "completed",
                Map.of(
                        "codegen_files",
                        List.of(
                                Map.of(
                                        "path", "frontend/src/App.vue",
                                        "content", "<template><main>runtime frontend</main></template>"
                                ),
                                Map.of(
                                        "path", "frontend/src/main.ts",
                                        "content", "console.log('runtime frontend')"
                                )
                        )
                )
        );

        when(runtimeGraphClient.runCodegenGraph(eq("task-runtime-frontend"), eq("project-runtime-frontend"), eq(12L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-runtime-frontend"), eq("project-runtime-frontend"), eq(12L), any(Map.class));
        verify(modelService, never()).generateFileContent(any(), any(), any(), any(), any(), any(), any());
        assertThat(Files.readString(tempDir.resolve("frontend/src/App.vue"))).contains("runtime frontend");
        assertThat(Files.readString(tempDir.resolve("frontend/src/main.ts"))).contains("runtime frontend");
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-frontend");
        task.setProjectId("project-runtime-frontend");
        task.setUserId(12L);
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"frontend test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        FilePlanItem appVue = new FilePlanItem();
        appVue.setPath("frontend/src/App.vue");
        appVue.setGroup("frontend");
        appVue.setPriority(10);
        FilePlanItem mainTs = new FilePlanItem();
        mainTs.setPath("frontend/src/main.ts");
        mainTs.setGroup("frontend");
        mainTs.setPriority(20);
        context.setFilePlan(List.of(appVue, mainTs));
        return context;
    }
}
