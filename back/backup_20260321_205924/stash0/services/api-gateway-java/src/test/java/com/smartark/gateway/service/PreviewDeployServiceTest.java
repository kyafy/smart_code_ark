package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewDeployServiceTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private PreviewRuntimeService previewRuntimeService;

    @InjectMocks
    private PreviewDeployService previewDeployService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(previewDeployService, "previewEnabled", true);
        ReflectionTestUtils.setField(previewDeployService, "autoDeployOnFinish", true);
        ReflectionTestUtils.setField(previewDeployService, "previewDefaultTtlHours", 24);
    }

    @Test
    void deployPreviewAsync_shouldSaveProvisioningThenReady_forFinishedGenerateTask() throws Exception {
        List<TaskPreviewEntity> snapshots = new ArrayList<>();
        TaskEntity task = buildTask("t1", "generate", "finished");
        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t1")).thenReturn(Optional.empty());
        when(previewRuntimeService.startRuntime("t1"))
                .thenReturn(new PreviewRuntimeService.RuntimeInfo("rt1", "http://localhost:33001"));
        when(taskPreviewRepository.save(any(TaskPreviewEntity.class))).thenAnswer(invocation -> {
            TaskPreviewEntity arg = invocation.getArgument(0);
            TaskPreviewEntity copy = new TaskPreviewEntity();
            copy.setStatus(arg.getStatus());
            copy.setPreviewUrl(arg.getPreviewUrl());
            copy.setRuntimeId(arg.getRuntimeId());
            copy.setExpireAt(arg.getExpireAt());
            copy.setLastError(arg.getLastError());
            copy.setUpdatedAt(arg.getUpdatedAt() == null ? null : LocalDateTime.from(arg.getUpdatedAt()));
            snapshots.add(copy);
            return arg;
        });

        previewDeployService.deployPreviewAsync("t1");

        verify(taskPreviewRepository, times(2)).save(any(TaskPreviewEntity.class));
        assertThat(snapshots.get(0).getStatus()).isEqualTo("provisioning");
        assertThat(snapshots.get(1).getStatus()).isEqualTo("ready");
        assertThat(snapshots.get(1).getPreviewUrl()).isEqualTo("http://localhost:33001");
        assertThat(snapshots.get(1).getRuntimeId()).isEqualTo("rt1");
        assertThat(snapshots.get(1).getExpireAt()).isNotNull();

        verify(taskRepository, times(1)).save(task);
        assertThat(task.getResultUrl()).isEqualTo("http://localhost:33001");
        verify(previewRuntimeService).startRuntime("t1");

        ArgumentCaptor<TaskLogEntity> logCaptor = ArgumentCaptor.forClass(TaskLogEntity.class);
        verify(taskLogRepository, times(2)).save(logCaptor.capture());
        List<TaskLogEntity> logs = logCaptor.getAllValues();
        assertThat(logs.get(0).getContent()).contains("Preview deployment started");
        assertThat(logs.get(1).getContent()).contains("Preview deployment succeeded");
    }

    @Test
    void deployPreviewAsync_shouldSkipWhenTaskIsNotPreviewTarget() throws Exception {
        TaskEntity task = buildTask("t2", "paper_outline", "finished");
        when(taskRepository.findById("t2")).thenReturn(Optional.of(task));

        previewDeployService.deployPreviewAsync("t2");

        verify(taskPreviewRepository, never()).save(any(TaskPreviewEntity.class));
        verify(taskLogRepository, never()).save(any(TaskLogEntity.class));
        verify(taskRepository, never()).save(any(TaskEntity.class));
        verify(previewRuntimeService, never()).startRuntime(any(String.class));
    }

    @Test
    void deployPreviewAsync_shouldSaveProvisioningThenReady_forFinishedModifyTask() throws Exception {
        TaskEntity task = buildTask("t3", "modify", "finished");
        when(taskRepository.findById("t3")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t3")).thenReturn(Optional.empty());
        when(previewRuntimeService.startRuntime("t3"))
                .thenReturn(new PreviewRuntimeService.RuntimeInfo("rt3", "http://localhost:33003"));
        when(taskPreviewRepository.save(any(TaskPreviewEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        previewDeployService.deployPreviewAsync("t3");

        ArgumentCaptor<TaskPreviewEntity> previewCaptor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository, times(2)).save(previewCaptor.capture());
        List<TaskPreviewEntity> savedPreviews = previewCaptor.getAllValues();
        assertThat(savedPreviews.get(1).getStatus()).isEqualTo("ready");
        assertThat(savedPreviews.get(1).getPreviewUrl()).isEqualTo("http://localhost:33003");
        verify(previewRuntimeService).startRuntime("t3");
    }

    @Test
    void deployPreviewAsync_shouldMarkFailedWhenRuntimeStartThrows() throws Exception {
        List<TaskPreviewEntity> snapshots = new ArrayList<>();
        TaskEntity task = buildTask("t4", "generate", "finished");
        TaskPreviewEntity existing = new TaskPreviewEntity();
        existing.setTaskId("t4");
        existing.setProjectId("p1");
        existing.setUserId(1L);
        existing.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        existing.setStatus("provisioning");
        when(taskRepository.findById("t4")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t4")).thenReturn(Optional.of(existing));
        when(previewRuntimeService.startRuntime("t4")).thenThrow(new RuntimeException("start failed"));
        when(taskPreviewRepository.save(any(TaskPreviewEntity.class))).thenAnswer(invocation -> {
            TaskPreviewEntity arg = invocation.getArgument(0);
            TaskPreviewEntity copy = new TaskPreviewEntity();
            copy.setStatus(arg.getStatus());
            copy.setPreviewUrl(arg.getPreviewUrl());
            copy.setRuntimeId(arg.getRuntimeId());
            copy.setLastError(arg.getLastError());
            copy.setUpdatedAt(arg.getUpdatedAt());
            snapshots.add(copy);
            return arg;
        });

        previewDeployService.deployPreviewAsync("t4");

        verify(taskPreviewRepository, times(2)).save(any(TaskPreviewEntity.class));
        assertThat(snapshots.get(0).getStatus()).isEqualTo("provisioning");
        assertThat(snapshots.get(1).getStatus()).isEqualTo("failed");
        assertThat(snapshots.get(1).getLastError()).contains("[", "start failed");
        verify(taskRepository, never()).save(any(TaskEntity.class));
    }

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
