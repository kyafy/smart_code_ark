package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RuntimeSmokeTestServiceTest {

    @Mock
    private ContainerRuntimeService containerRuntimeService;

    @TempDir
    Path tempDir;

    @Test
    void verify_shouldPassDeliverableWhenRuntimeSmokeSucceeds() throws Exception {
        TemplateRepoService templateRepoService = buildTemplateRepoService();
        RuntimeSmokeTestService service = new RuntimeSmokeTestService(
                new ObjectMapper(),
                new FrontendRuntimePlanService(new ObjectMapper(), templateRepoService)
        );
        ReflectionTestUtils.setField(service, "containerRuntimeService", containerRuntimeService);
        ReflectionTestUtils.setField(service, "runtimeSmokeEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "healthCheckTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "healthCheckIntervalMs", 100);

        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/package.json"), """
                {
                  "scripts": {
                    "dev": "vite",
                    "build": "vite build"
                  }
                }
                """);
        writeBuildVerifyReport(workspace, true);

        when(containerRuntimeService.findAvailablePort()).thenReturn(30001);
        when(containerRuntimeService.createAndStartContainer(anyString(), anyInt(), anyString())).thenReturn("container-1");
        when(containerRuntimeService.execInContainer("container-1", "sh", "-c", "npm install --prefer-offline 2>&1"))
                .thenReturn(new ContainerRuntimeService.ExecResult(0, "ok"));
        when(containerRuntimeService.checkHealth("localhost", 30001, 5, 100)).thenReturn(true);
        when(containerRuntimeService.getContainerLogs("container-1", 200)).thenReturn("service ready");

        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-pass");
        task.setTemplateId("springboot-vue3-mysql");
        task.setDeliveryLevelRequested("deliverable");

        RuntimeSmokeTestService.RuntimeSmokeTestBundle bundle = service.verify(task, workspace);

        assertThat(bundle.runtimeReport().passed()).isTrue();
        assertThat(bundle.deliveryReport().deliveryLevelActual()).isEqualTo("deliverable");
        assertThat(bundle.deliveryReport().status()).isEqualTo("passed");
        assertThat(bundle.deliveryReport().passed()).isTrue();
        assertThat(Files.exists(workspace.resolve("runtime_smoke_test_report.json"))).isTrue();
        assertThat(Files.exists(workspace.resolve("delivery_report.json"))).isTrue();
        verify(containerRuntimeService).stopAndRemoveContainer("container-1");
    }

    @Test
    void verify_shouldDegradeDeliverableWhenRuntimeCommandExecutionDisabled() throws Exception {
        TemplateRepoService templateRepoService = buildTemplateRepoService();
        RuntimeSmokeTestService service = new RuntimeSmokeTestService(
                new ObjectMapper(),
                new FrontendRuntimePlanService(new ObjectMapper(), templateRepoService)
        );
        ReflectionTestUtils.setField(service, "runtimeSmokeEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", false);
        ReflectionTestUtils.setField(service, "healthCheckTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "healthCheckIntervalMs", 100);

        Path workspace = tempDir.resolve("workspace-disabled");
        Files.createDirectories(workspace);
        writeBuildVerifyReport(workspace, true);

        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-disabled");
        task.setTemplateId("springboot-vue3-mysql");
        task.setDeliveryLevelRequested("deliverable");

        RuntimeSmokeTestService.RuntimeSmokeTestBundle bundle = service.verify(task, workspace);

        assertThat(bundle.runtimeReport().passed()).isFalse();
        assertThat(bundle.runtimeReport().blockingIssues())
                .extracting(issue -> issue.code())
                .contains("runtime_smoke_command_execution_disabled");
        assertThat(bundle.deliveryReport().deliveryLevelActual()).isEqualTo("validated");
        assertThat(bundle.deliveryReport().status()).isEqualTo("degraded");
        assertThat(bundle.deliveryReport().passed()).isFalse();
    }

    @Test
    void verify_shouldRetainReusableRuntimeForPreviewWhenPreviewEnabled() throws Exception {
        TemplateRepoService templateRepoService = buildTemplateRepoService();
        RuntimeSmokeTestService service = new RuntimeSmokeTestService(
                new ObjectMapper(),
                new FrontendRuntimePlanService(new ObjectMapper(), templateRepoService)
        );
        ReflectionTestUtils.setField(service, "containerRuntimeService", containerRuntimeService);
        ReflectionTestUtils.setField(service, "runtimeSmokeEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "healthCheckTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "healthCheckIntervalMs", 100);
        ReflectionTestUtils.setField(service, "keepRuntimeForPreviewEnabled", true);
        ReflectionTestUtils.setField(service, "previewEnabled", true);
        ReflectionTestUtils.setField(service, "previewAutoDeployOnFinish", true);

        Path workspace = tempDir.resolve("workspace-reuse");
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("frontend"));
        Files.writeString(workspace.resolve("frontend/package.json"), """
                {
                  "scripts": {
                    "dev": "vite"
                  }
                }
                """);
        writeBuildVerifyReport(workspace, true);

        when(containerRuntimeService.findAvailablePort()).thenReturn(30011);
        when(containerRuntimeService.createAndStartContainer(anyString(), anyInt(), anyString())).thenReturn("container-reuse");
        when(containerRuntimeService.execInContainer("container-reuse", "sh", "-c", "npm install --prefer-offline 2>&1"))
                .thenReturn(new ContainerRuntimeService.ExecResult(0, "ok"));
        when(containerRuntimeService.checkHealth("localhost", 30011, 5, 100)).thenReturn(true);
        when(containerRuntimeService.getContainerLogs("container-reuse", 200)).thenReturn("service ready");

        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-reuse");
        task.setTemplateId("springboot-vue3-mysql");
        task.setDeliveryLevelRequested("deliverable");

        RuntimeSmokeTestService.RuntimeSmokeTestBundle bundle = service.verify(task, workspace);

        assertThat(bundle.runtimeReport().reusableRuntime()).isNotNull();
        assertThat(bundle.runtimeReport().reusableRuntime().availableForPreview()).isTrue();
        assertThat(bundle.runtimeReport().reusableRuntime().runtimeId()).isEqualTo("container-reuse");
        verify(containerRuntimeService, org.mockito.Mockito.never()).stopAndRemoveContainer("container-reuse");
    }

    private void writeBuildVerifyReport(Path workspace, boolean passed) throws Exception {
        BuildVerifyReportResult report = new BuildVerifyReportResult(
                passed,
                false,
                "deliverable",
                List.of(),
                List.of(),
                List.of(),
                "2026-03-24T12:00:00"
        );
        Files.writeString(
                workspace.resolve("build_verify_report.json"),
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report),
                StandardCharsets.UTF_8
        );
    }

    private TemplateRepoService buildTemplateRepoService() throws Exception {
        Path repoRoot = tempDir.resolve("template-repo");
        Files.createDirectories(repoRoot.resolve("templates/springboot-vue3-mysql"));
        Files.writeString(
                repoRoot.resolve("catalog.json"),
                """
                {
                  "version": "1.0.0",
                  "templates": [
                    {
                      "key": "springboot-vue3-mysql",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    }
                  ]
                }
                """
        );
        Files.writeString(
                repoRoot.resolve("templates/springboot-vue3-mysql/template.json"),
                """
                {
                  "key": "springboot-vue3-mysql",
                  "name": "Spring Boot + Vue 3 + MySQL",
                  "stack": {
                    "backend": "Spring Boot",
                    "frontend": "Vue 3 + Vite"
                  },
                  "run": {
                    "frontend": "cd frontend && npm install && npm run dev"
                  }
                }
                """
        );
        return new TemplateRepoService(new ObjectMapper(), repoRoot.toString());
    }
}
