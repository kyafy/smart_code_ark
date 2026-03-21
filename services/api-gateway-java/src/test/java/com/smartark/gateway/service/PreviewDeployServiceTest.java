package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.TaskPreviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewDeployServiceTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private PreviewSseRegistry previewSseRegistry;

    private PreviewDeployService previewDeployService;

    @TempDir
    Path tempDir;

    /** Records a snapshot of each save call's status field to track state transitions */
    private final List<String> savedStatuses = new ArrayList<>();
    private final List<String> savedPhases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previewDeployService = new PreviewDeployService(taskPreviewRepository, taskRepository, taskLogRepository);
        ReflectionTestUtils.setField(previewDeployService, "previewSseRegistry", previewSseRegistry);
        ReflectionTestUtils.setField(previewDeployService, "previewEnabled", true);
        ReflectionTestUtils.setField(previewDeployService, "autoDeployOnFinish", true);
        ReflectionTestUtils.setField(previewDeployService, "previewDefaultTtlHours", 24);
        ReflectionTestUtils.setField(previewDeployService, "previewMaxConcurrentPerUser", 2);
        ReflectionTestUtils.setField(previewDeployService, "workspaceRoot", "/tmp/smartark/");
        ReflectionTestUtils.setField(previewDeployService, "previewLogDir", "/tmp/smartark/preview-logs");
        ReflectionTestUtils.setField(previewDeployService, "healthCheckTimeoutSeconds", 60);
        ReflectionTestUtils.setField(previewDeployService, "healthCheckIntervalMs", 3000);

        savedStatuses.clear();
        savedPhases.clear();

        // Capture status snapshots on each save
        when(taskPreviewRepository.save(any(TaskPreviewEntity.class))).thenAnswer(invocation -> {
            TaskPreviewEntity entity = invocation.getArgument(0);
            savedStatuses.add(entity.getStatus());
            savedPhases.add(entity.getPhase());
            return entity;
        });
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ===== Static Fallback Tests (containerRuntimeService == null) =====

    @Test
    void deployPreviewAsync_staticFallback_shouldTransitionProvisioningToReady() {
        TaskEntity task = buildTask("t1", "generate", "finished");
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t1")).thenReturn(Optional.empty());

        previewDeployService.deployPreviewAsync("t1");

        // Should save provisioning then ready
        assertThat(savedStatuses).containsExactly("provisioning", "ready");
        assertThat(task.getResultUrl()).isEqualTo("http://localhost:5173/preview/t1");
    }

    @Test
    void deployPreviewAsync_staticFallback_shouldSetCorrectFieldsOnReady() {
        TaskEntity task = buildTask("t1b", "generate", "finished");
        when(taskRepository.findById("t1b")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t1b")).thenReturn(Optional.empty());

        previewDeployService.deployPreviewAsync("t1b");

        // Verify final state via the last save
        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository, times(2)).save(captor.capture());
        TaskPreviewEntity finalEntity = captor.getValue(); // last captured (same ref)
        assertThat(finalEntity.getStatus()).isEqualTo("ready");
        assertThat(finalEntity.getPhase()).isNull();
        assertThat(finalEntity.getPreviewUrl()).isEqualTo("http://localhost:5173/preview/t1b");
        assertThat(finalEntity.getExpireAt()).isNotNull();
        assertThat(finalEntity.getLastError()).isNull();
        assertThat(finalEntity.getLastErrorCode()).isNull();
    }

    @Test
    void deployPreviewAsync_staticFallback_shouldWorkForModifyTaskType() {
        TaskEntity task = buildTask("t2", "modify", "finished");
        when(taskRepository.findById("t2")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t2")).thenReturn(Optional.empty());

        previewDeployService.deployPreviewAsync("t2");

        assertThat(savedStatuses).containsExactly("provisioning", "ready");
    }

    @Test
    void deployPreviewAsync_shouldLogDeploymentStartAndSuccess() {
        TaskEntity task = buildTask("t3", "generate", "finished");
        when(taskRepository.findById("t3")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t3")).thenReturn(Optional.empty());

        previewDeployService.deployPreviewAsync("t3");

        ArgumentCaptor<TaskLogEntity> logCaptor = ArgumentCaptor.forClass(TaskLogEntity.class);
        verify(taskLogRepository, times(2)).save(logCaptor.capture());
        List<TaskLogEntity> logs = logCaptor.getAllValues();
        assertThat(logs.get(0).getContent()).contains("Preview deployment started");
        assertThat(logs.get(1).getContent()).contains("static fallback");
    }

    // ===== Skip Conditions Tests =====

    @Test
    void deployPreviewAsync_shouldSkipWhenTaskNotFound() {
        when(taskRepository.findById("not-exist")).thenReturn(Optional.empty());

        previewDeployService.deployPreviewAsync("not-exist");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
    }

    @Test
    void deployPreviewAsync_shouldSkipWhenTaskIsNotPreviewTarget() {
        TaskEntity task = buildTask("t4", "paper_outline", "finished");
        when(taskRepository.findById("t4")).thenReturn(Optional.of(task));

        previewDeployService.deployPreviewAsync("t4");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
    }

    @Test
    void deployPreviewAsync_shouldSkipWhenTaskStatusIsNotFinished() {
        TaskEntity task = buildTask("t5", "generate", "running");
        when(taskRepository.findById("t5")).thenReturn(Optional.of(task));

        previewDeployService.deployPreviewAsync("t5");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
    }

    @Test
    void deployPreviewAsync_shouldSkipWhenPreviewDisabled() {
        ReflectionTestUtils.setField(previewDeployService, "previewEnabled", false);
        TaskEntity task = buildTask("t6", "generate", "finished");
        when(taskRepository.findById("t6")).thenReturn(Optional.of(task));

        previewDeployService.deployPreviewAsync("t6");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
    }

    @Test
    void deployPreviewAsync_shouldSkipWhenAutoDeployDisabled() {
        ReflectionTestUtils.setField(previewDeployService, "autoDeployOnFinish", false);
        TaskEntity task = buildTask("t7", "generate", "finished");
        when(taskRepository.findById("t7")).thenReturn(Optional.of(task));

        previewDeployService.deployPreviewAsync("t7");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
    }

    // ===== Quota Tests =====

    @Test
    void deployPreviewAsync_shouldFailWhenQuotaExceeded() {
        TaskEntity task = buildTask("t8", "generate", "finished");
        when(taskRepository.findById("t8")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t8")).thenReturn(Optional.empty());
        when(taskPreviewRepository.countByUserIdAndStatusIn(eq(1L), any())).thenReturn(2L);

        previewDeployService.deployPreviewAsync("t8");

        assertThat(savedStatuses).containsExactly("failed");
        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getLastError()).contains("并发数已达上限");
        assertThat(captor.getValue().getLastErrorCode()).isEqualTo(ErrorCodes.PREVIEW_CONCURRENCY_LIMIT);
    }

    // ===== Existing Preview Record Tests =====

    @Test
    void deployPreviewAsync_shouldReuseExistingPreviewRecord() {
        TaskEntity task = buildTask("t9", "generate", "finished");
        TaskPreviewEntity existing = new TaskPreviewEntity();
        existing.setId(100L);
        existing.setTaskId("t9");
        existing.setProjectId("p1");
        existing.setUserId(1L);
        existing.setStatus("failed");

        when(taskRepository.findById("t9")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t9")).thenReturn(Optional.of(existing));

        previewDeployService.deployPreviewAsync("t9");

        assertThat(savedStatuses).containsExactly("provisioning", "ready");

        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository, times(2)).save(captor.capture());
        // Should reuse the same entity (id=100)
        assertThat(captor.getValue().getId()).isEqualTo(100L);
    }

    // ===== Container Deploy Tests (with mocked ContainerRuntimeService) =====

    @Test
    void deployPreviewAsync_withContainerRuntime_shouldGoThroughAllPhases() throws IOException {
        ContainerRuntimeService containerRuntime = org.mockito.Mockito.mock(ContainerRuntimeService.class);
        ReflectionTestUtils.setField(previewDeployService, "containerRuntimeService", containerRuntime);
        ReflectionTestUtils.setField(previewDeployService, "workspaceRoot", tempDir.toString());
        ReflectionTestUtils.setField(previewDeployService, "previewLogDir", tempDir.resolve("logs").toString());

        // Create workspace with package.json so resolveFrontendPath finds it
        Path workspace = tempDir.resolve("t10");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");

        TaskEntity task = buildTask("t10", "generate", "finished");
        when(taskRepository.findById("t10")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t10")).thenReturn(Optional.empty());

        when(containerRuntime.findAvailablePort()).thenReturn(30001);
        when(containerRuntime.createAndStartContainer(any(), eq(30001), eq("t10"))).thenReturn("container-abc");
        when(containerRuntime.execInContainer(eq("container-abc"), any(), any(), any()))
                .thenReturn(new ContainerRuntimeService.ExecResult(0, "npm install done"));
        when(containerRuntime.checkHealth(eq("localhost"), eq(30001), eq(60), eq(3000))).thenReturn(true);

        previewDeployService.deployPreviewAsync("t10");

        // Verify phase progression: provisioning → 6 phases → ready
        assertThat(savedStatuses.get(0)).isEqualTo("provisioning");
        // Some phases have extra saves (runtimeId, buildLogUrl, healthCheckAt), so use containsSubsequence
        assertThat(savedPhases).containsSubsequence(
                null, // provisioning save
                "prepare_artifact",
                "start_runtime",
                "install_deps",
                "boot_service",
                "health_check",
                "publish_gateway",
                null  // ready save (phase cleared)
        );

        // Final state should be ready
        assertThat(savedStatuses.get(savedStatuses.size() - 1)).isEqualTo("ready");

        // Verify SSE broadcast: 6 phases + 1 ready
        verify(previewSseRegistry, times(7)).broadcast(eq("t10"), any(TaskPreviewResult.class));
    }

    @Test
    void deployPreviewAsync_withContainerRuntime_shouldFailOnInstallError() throws IOException {
        ContainerRuntimeService containerRuntime = org.mockito.Mockito.mock(ContainerRuntimeService.class);
        ReflectionTestUtils.setField(previewDeployService, "containerRuntimeService", containerRuntime);
        ReflectionTestUtils.setField(previewDeployService, "workspaceRoot", tempDir.toString());
        ReflectionTestUtils.setField(previewDeployService, "previewLogDir", tempDir.resolve("logs").toString());

        Path workspace = tempDir.resolve("t11");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");

        TaskEntity task = buildTask("t11", "generate", "finished");
        when(taskRepository.findById("t11")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t11")).thenReturn(Optional.empty());

        when(containerRuntime.findAvailablePort()).thenReturn(30002);
        when(containerRuntime.createAndStartContainer(any(), eq(30002), eq("t11"))).thenReturn("container-def");
        when(containerRuntime.execInContainer(eq("container-def"), any(), any(), any()))
                .thenReturn(new ContainerRuntimeService.ExecResult(1, "npm ERR! missing package.json"));

        previewDeployService.deployPreviewAsync("t11");

        // Last status should be failed
        assertThat(savedStatuses.get(savedStatuses.size() - 1)).isEqualTo("failed");

        // Verify SSE broadcast: 3 phases (prepare_artifact, start_runtime, install_deps) + 1 failure
        verify(previewSseRegistry, times(4)).broadcast(eq("t11"), any(TaskPreviewResult.class));
    }

    @Test
    void deployPreviewAsync_withContainerRuntime_shouldFailOnHealthCheckTimeout() throws IOException {
        ContainerRuntimeService containerRuntime = org.mockito.Mockito.mock(ContainerRuntimeService.class);
        ReflectionTestUtils.setField(previewDeployService, "containerRuntimeService", containerRuntime);
        ReflectionTestUtils.setField(previewDeployService, "workspaceRoot", tempDir.toString());
        ReflectionTestUtils.setField(previewDeployService, "previewLogDir", tempDir.resolve("logs").toString());

        Path workspace = tempDir.resolve("t12");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");

        TaskEntity task = buildTask("t12", "generate", "finished");
        when(taskRepository.findById("t12")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t12")).thenReturn(Optional.empty());

        when(containerRuntime.findAvailablePort()).thenReturn(30003);
        when(containerRuntime.createAndStartContainer(any(), eq(30003), eq("t12"))).thenReturn("container-ghi");
        when(containerRuntime.execInContainer(eq("container-ghi"), any(), any(), any()))
                .thenReturn(new ContainerRuntimeService.ExecResult(0, "ok"));
        when(containerRuntime.checkHealth("localhost", 30003, 60, 3000)).thenReturn(false);
        when(containerRuntime.getContainerLogs("container-ghi", 50)).thenReturn("vite: command not found");

        previewDeployService.deployPreviewAsync("t12");

        assertThat(savedStatuses.get(savedStatuses.size() - 1)).isEqualTo("failed");
    }

    // ===== Phase Constants Tests =====

    @Test
    void phaseConstants_shouldHaveCorrectValues() {
        assertThat(PreviewDeployService.PHASE_PREPARE_ARTIFACT).isEqualTo("prepare_artifact");
        assertThat(PreviewDeployService.PHASE_START_RUNTIME).isEqualTo("start_runtime");
        assertThat(PreviewDeployService.PHASE_INSTALL_DEPS).isEqualTo("install_deps");
        assertThat(PreviewDeployService.PHASE_BOOT_SERVICE).isEqualTo("boot_service");
        assertThat(PreviewDeployService.PHASE_HEALTH_CHECK).isEqualTo("health_check");
        assertThat(PreviewDeployService.PHASE_PUBLISH_GATEWAY).isEqualTo("publish_gateway");
    }

    // ===== Helper Methods =====

    private TaskEntity buildTask(String taskId, String taskType, String status) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId("p1");
        task.setUserId(1L);
        task.setTaskType(taskType);
        task.setStatus(status);
        task.setProgress(100);
        return task;
    }
}
