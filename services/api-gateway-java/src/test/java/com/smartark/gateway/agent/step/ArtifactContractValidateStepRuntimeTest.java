package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactContractValidateStepRuntimeTest {

    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @TempDir
    Path tempDir;

    @Test
    void execute_usesRuntimeValidationWhenEnabled() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep(runtimeGraphClient, true);
        Path workspace = createWorkspaceWithOnlyCompose();
        AgentExecutionContext context = buildContext(workspace, "task-acv-runtime");

        when(runtimeGraphClient.runCodegenGraph(eq("task-acv-runtime"), eq("project-acv"), eq(15L), any(Map.class)))
                .thenReturn(new LangchainGraphRunResult(
                        "run-acv-1",
                        "task-acv-runtime",
                        "codegen",
                        "completed",
                        Map.of(
                                "violations", java.util.List.of(),
                                "fatal_violations", java.util.List.of()
                        )
                ));

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-acv-runtime"), eq("project-acv"), eq(15L), any(Map.class));
        assertThat(context.getContractViolations()).isEmpty();
    }

    @Test
    void execute_fallbacksToLocalChecksWhenRuntimeResultInvalid() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep(runtimeGraphClient, true);
        Path workspace = createWorkspaceWithOnlyCompose();
        AgentExecutionContext context = buildContext(workspace, "task-acv-fallback");

        when(runtimeGraphClient.runCodegenGraph(eq("task-acv-fallback"), eq("project-acv"), eq(15L), any(Map.class)))
                .thenReturn(new LangchainGraphRunResult(
                        "run-acv-2",
                        "task-acv-fallback",
                        "codegen",
                        "completed",
                        Map.of("summary", "invalid payload")
                ));

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-acv-fallback"), eq("project-acv"), eq(15L), any(Map.class));
        assertThat(context.getContractViolations()).anyMatch(v -> v.contains("README.md"));
    }

    @Test
    void execute_skipsRuntimeWhenDisabled() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep(runtimeGraphClient, false);
        Path workspace = createWorkspaceWithOnlyCompose();
        AgentExecutionContext context = buildContext(workspace, "task-acv-disabled");

        step.execute(context);

        verify(runtimeGraphClient, never()).runCodegenGraph(any(), any(), any(), any(Map.class));
        assertThat(context.getContractViolations()).anyMatch(v -> v.contains("README.md"));
    }

    private Path createWorkspaceWithOnlyCompose() throws Exception {
        Path workspace = tempDir.resolve("ws-" + System.nanoTime());
        Files.createDirectories(workspace.resolve("backend"));
        Files.writeString(
                workspace.resolve("docker-compose.yml"),
                "services:\n  backend:\n    build:\n      context: ./backend\n",
                StandardCharsets.UTF_8
        );
        return workspace;
    }

    private AgentExecutionContext buildContext(Path workspace, String taskId) {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId("project-acv");
        task.setUserId(15L);
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        return context;
    }
}
