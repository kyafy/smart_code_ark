package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.BuildVerifyService;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuildVerifyStepRuntimeTest {

    @Mock
    private BuildVerifyService buildVerifyService;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private ModelService modelService;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @TempDir
    Path tempDir;

    @Test
    void execute_usesRuntimeBuildAutofixWhenEnabled() throws Exception {
        BuildVerifyStep step = new BuildVerifyStep(
                buildVerifyService,
                taskRepository,
                modelService,
                runtimeGraphClient,
                true
        );
        ReflectionTestUtils.setField(step, "autoFixEnabled", true);
        ReflectionTestUtils.setField(step, "autoFixMaxRetries", 1);

        AgentExecutionContext context = buildContext("task-build-runtime", "project-build-runtime", 77L);
        Path sourceFile = context.getWorkspaceDir().resolve("frontend/src/App.ts");
        Path logFile = context.getWorkspaceDir().resolve("logs/frontend-build.log");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(logFile.getParent());
        Files.writeString(sourceFile, "export const app = ;\n");
        Files.writeString(logFile, "frontend/src/App.ts(1,20): error TS1109: Expression expected");

        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(buildVerifyService.verify(any(TaskEntity.class), any(Path.class)))
                .thenReturn(bundle("task-build-runtime", false, "failed", "logs/frontend-build.log"))
                .thenReturn(bundle("task-build-runtime", true, "passed", null));
        when(runtimeGraphClient.runCodegenGraph(eq("task-build-runtime"), eq("project-build-runtime"), eq(77L), any(Map.class)))
                .thenAnswer(invocation -> {
                    Map<String, Object> input = invocation.getArgument(3);
                    String stage = String.valueOf(input.get("stage"));
                    if ("build_verify_batch_autofix".equals(stage)) {
                        return new LangchainGraphRunResult(
                                "run-build-fix-batch",
                                "task-build-runtime",
                                "codegen",
                                "completed",
                                Map.of("fixed_files", List.of(Map.of(
                                        "path", "frontend/src/App.ts",
                                        "fixed_content", "export const app = 1;\n"
                                )))
                        );
                    }
                    return new LangchainGraphRunResult(
                            "run-build-fix-single",
                            "task-build-runtime",
                            "codegen",
                            "completed",
                            Map.of("summary", "no fix")
                    );
                });

        step.execute(context);

        verify(runtimeGraphClient, atLeastOnce()).runCodegenGraph(
                eq("task-build-runtime"),
                eq("project-build-runtime"),
                eq(77L),
                argThat(input -> "build_verify_batch_autofix".equals(String.valueOf(input.get("stage"))))
        );
        verify(modelService, never()).fixCompilationError(any(), any(), any(), any(), any(), any());
        assertThat(Files.readString(sourceFile)).contains("export const app = 1;");
    }

    @Test
    void execute_fallbacksToModelServiceWhenRuntimeReturnsEmptyFix() throws Exception {
        BuildVerifyStep step = new BuildVerifyStep(
                buildVerifyService,
                taskRepository,
                modelService,
                runtimeGraphClient,
                true
        );
        ReflectionTestUtils.setField(step, "autoFixEnabled", true);
        ReflectionTestUtils.setField(step, "autoFixMaxRetries", 1);

        AgentExecutionContext context = buildContext("task-build-fallback", "project-build-fallback", 88L);
        Path sourceFile = context.getWorkspaceDir().resolve("frontend/src/App.ts");
        Path logFile = context.getWorkspaceDir().resolve("logs/frontend-build.log");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(logFile.getParent());
        Files.writeString(sourceFile, "export const app = ;\n");
        Files.writeString(logFile, "frontend/src/App.ts(1,20): error TS1109: Expression expected");

        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(buildVerifyService.verify(any(TaskEntity.class), any(Path.class)))
                .thenReturn(bundle("task-build-fallback", false, "failed", "logs/frontend-build.log"))
                .thenReturn(bundle("task-build-fallback", true, "passed", null));
        when(runtimeGraphClient.runCodegenGraph(eq("task-build-fallback"), eq("project-build-fallback"), eq(88L), any(Map.class)))
                .thenReturn(new LangchainGraphRunResult(
                        "run-build-fallback",
                        "task-build-fallback",
                        "codegen",
                        "completed",
                        Map.of("summary", "no fix")
                ));
        when(modelService.fixCompilationError(
                eq("task-build-fallback"),
                eq("project-build-fallback"),
                eq("frontend/src/App.ts"),
                any(String.class),
                any(String.class),
                any(String.class)
        )).thenReturn("export const app = 2;\n");

        step.execute(context);

        verify(runtimeGraphClient, atLeastOnce()).runCodegenGraph(
                eq("task-build-fallback"),
                eq("project-build-fallback"),
                eq(88L),
                argThat(input -> "build_verify_batch_autofix".equals(String.valueOf(input.get("stage"))))
        );
        verify(modelService).fixCompilationError(
                eq("task-build-fallback"),
                eq("project-build-fallback"),
                eq("frontend/src/App.ts"),
                any(String.class),
                any(String.class),
                any(String.class)
        );
        assertThat(Files.readString(sourceFile)).contains("export const app = 2;");
    }

    private AgentExecutionContext buildContext(String taskId, String projectId, Long userId) {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setDeliveryLevelRequested("deliverable");
        context.setTask(task);
        context.setWorkspaceDir(tempDir.resolve(taskId));

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);
        return context;
    }

    private BuildVerifyService.BuildVerifyBundle bundle(String taskId, boolean passed, String status, String logRef) {
        BuildVerifyReportResult buildReport = new BuildVerifyReportResult(
                passed,
                false,
                "deliverable",
                List.of(new BuildVerifyReportResult.CommandResult(
                        "frontend-build",
                        "npm run build",
                        ".",
                        passed ? 0 : 1,
                        100L,
                        passed ? "passed" : "failed",
                        logRef
                )),
                List.of(),
                List.of(),
                "2026-03-25T00:00:00Z"
        );
        DeliveryReportResult deliveryReport = new DeliveryReportResult(
                taskId,
                "deliverable",
                passed ? "deliverable" : "draft",
                status,
                passed,
                List.of(),
                List.of(),
                new DeliveryReportResult.ReportRefs(null, null, null),
                "2026-03-25T00:00:00Z"
        );
        return new BuildVerifyService.BuildVerifyBundle(buildReport, deliveryReport);
    }
}
