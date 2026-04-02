package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseDeployServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void detectK8sRollbackTargets_shouldExtractWorkloadsOnly() throws Exception {
        ReleaseDeployService service = buildService();
        ReflectionTestUtils.setField(service, "releaseK8sRollbackKinds", "deployment,statefulset,daemonset");

        Path k8sDir = tempDir.resolve("k8s");
        Files.createDirectories(k8sDir);
        Files.writeString(
                k8sDir.resolve("all.yaml"),
                """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: app-api
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: app-api
                ---
                apiVersion: apps/v1
                kind: StatefulSet
                metadata:
                  name: app-db
                """,
                StandardCharsets.UTF_8
        );

        List<String> targets = service.detectK8sRollbackTargets(k8sDir);
        assertThat(targets).contains("deployment/app-api", "statefulset/app-db");
        assertThat(targets).noneMatch(item -> item.startsWith("service/"));
    }

    @Test
    void rollbackIfNeeded_k8sShouldPlanRolloutUndoCommands() throws Exception {
        ReleaseDeployService service = buildService();
        ReflectionTestUtils.setField(service, "releaseEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "releaseK8sNamespace", "test-ns");

        Path workspace = tempDir.resolve("workspace");
        Path k8sDir = workspace.resolve("k8s");
        Files.createDirectories(k8sDir);
        Files.writeString(
                k8sDir.resolve("deployment.yaml"),
                """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: app-api
                """,
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.resolve(ReleaseDeployService.DEPLOY_REPORT_FILE),
                """
                {"passed":false,"skipped":false}
                """,
                StandardCharsets.UTF_8
        );

        TaskEntity task = new TaskEntity();
        task.setId("task-1");
        task.setProjectId("project-1");
        task.setDeployMode("k8s");
        task.setAutoDeployTarget(true);

        ReleaseDeployService.ReleaseReport report = service.rollbackIfNeeded(task, workspace);
        assertThat(report.skipped()).isFalse();
        assertThat(report.commands()).isNotEmpty();
        assertThat(report.commands().get(0).command()).contains("rollout undo deployment/app-api");
        assertThat(report.commands().get(0).command()).contains("-n test-ns");
    }

    private ReleaseDeployService buildService() {
        ReleaseDeployService service = new ReleaseDeployService(new ObjectMapper());
        ReflectionTestUtils.setField(service, "releaseEnabled", true);
        ReflectionTestUtils.setField(service, "commandExecutionEnabled", true);
        ReflectionTestUtils.setField(service, "timeoutSeconds", 5L);
        ReflectionTestUtils.setField(service, "registryPrefix", "");
        ReflectionTestUtils.setField(service, "verifyHealthUrl", "");
        ReflectionTestUtils.setField(service, "releaseK8sNamespace", "");
        ReflectionTestUtils.setField(service, "releaseK8sRollbackEnabled", true);
        ReflectionTestUtils.setField(service, "releaseK8sRollbackKinds", "deployment,statefulset,daemonset");
        return service;
    }
}
