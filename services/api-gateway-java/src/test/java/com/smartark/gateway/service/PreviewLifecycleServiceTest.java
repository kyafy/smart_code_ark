package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewLifecycleServiceTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private ContainerRuntimeService containerRuntimeService;
    @Mock
    private PreviewGatewayService previewGatewayService;

    private PreviewLifecycleService previewLifecycleService;

    @BeforeEach
    void setUp() {
        previewLifecycleService = new PreviewLifecycleService(taskPreviewRepository, taskLogRepository);
        ReflectionTestUtils.setField(previewLifecycleService, "containerRuntimeService", containerRuntimeService);
        ReflectionTestUtils.setField(previewLifecycleService, "previewGatewayService", previewGatewayService);
        ReflectionTestUtils.setField(previewLifecycleService, "previewEnabled", true);
        when(taskPreviewRepository.save(any(TaskPreviewEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void recycleExpiredPreviews_shouldMarkExpiredAndStopContainer() {
        TaskPreviewEntity preview = buildPreview("t1", "ready", "container-1");
        preview.setExpireAt(LocalDateTime.now().minusHours(1));

        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of(preview));

        previewLifecycleService.recycleExpiredPreviews();

        // Verify container stopped
        verify(containerRuntimeService).stopAndRemoveContainer("container-1");

        // Verify preview marked as expired
        ArgumentCaptor<TaskPreviewEntity> previewCaptor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository).save(previewCaptor.capture());
        assertThat(previewCaptor.getValue().getStatus()).isEqualTo("expired");
        assertThat(previewCaptor.getValue().getPhase()).isNull();

        // Verify task log
        ArgumentCaptor<TaskLogEntity> logCaptor = ArgumentCaptor.forClass(TaskLogEntity.class);
        verify(taskLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getContent()).contains("recycled as expired");
        verify(previewGatewayService).recycleExpiredRoutes();
        verify(previewGatewayService).unregisterRoute("t1");
    }

    @Test
    void recycleExpiredPreviews_shouldHandleMultipleExpiredPreviews() {
        TaskPreviewEntity p1 = buildPreview("t1", "ready", "c1");
        TaskPreviewEntity p2 = buildPreview("t2", "ready", "c2");
        p1.setExpireAt(LocalDateTime.now().minusMinutes(30));
        p2.setExpireAt(LocalDateTime.now().minusMinutes(10));

        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of(p1, p2));

        previewLifecycleService.recycleExpiredPreviews();

        verify(containerRuntimeService).stopAndRemoveContainer("c1");
        verify(containerRuntimeService).stopAndRemoveContainer("c2");
        verify(taskPreviewRepository, times(2)).save(any());
    }

    @Test
    void recycleExpiredPreviews_shouldHandleNullRuntimeId() {
        TaskPreviewEntity preview = buildPreview("t3", "ready", null);
        preview.setExpireAt(LocalDateTime.now().minusHours(1));

        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of(preview));

        previewLifecycleService.recycleExpiredPreviews();

        // Should not call stopAndRemoveContainer when runtimeId is null
        verify(containerRuntimeService, never()).stopAndRemoveContainer(any());
        verify(taskPreviewRepository).save(any());
    }

    @Test
    void recycleExpiredPreviews_shouldSkipWhenPreviewDisabled() {
        ReflectionTestUtils.setField(previewLifecycleService, "previewEnabled", false);

        previewLifecycleService.recycleExpiredPreviews();

        verify(taskPreviewRepository, never()).findByStatusAndExpireAtBefore(any(), any());
        verify(previewGatewayService, never()).recycleExpiredRoutes();
    }

    @Test
    void recycleExpiredPreviews_shouldDoNothingWhenNoExpired() {
        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        previewLifecycleService.recycleExpiredPreviews();

        verify(taskPreviewRepository, never()).save(any());
        verify(containerRuntimeService, never()).stopAndRemoveContainer(any());
    }

    @Test
    void recycleExpiredPreviews_shouldContinueOnContainerStopFailure() {
        TaskPreviewEntity p1 = buildPreview("t4", "ready", "c-bad");
        TaskPreviewEntity p2 = buildPreview("t5", "ready", "c-good");
        p1.setExpireAt(LocalDateTime.now().minusHours(1));
        p2.setExpireAt(LocalDateTime.now().minusHours(1));

        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of(p1, p2));

        org.mockito.Mockito.doThrow(new RuntimeException("docker error"))
                .when(containerRuntimeService).stopAndRemoveContainer("c-bad");

        previewLifecycleService.recycleExpiredPreviews();

        // p2 should still be processed even though p1 failed
        verify(containerRuntimeService).stopAndRemoveContainer("c-good");
    }

    @Test
    void recycleExpiredPreviews_withoutContainerRuntime_shouldStillMarkExpired() {
        // Simulate containerRuntimeService being null (preview.enabled=true but Docker unavailable)
        ReflectionTestUtils.setField(previewLifecycleService, "containerRuntimeService", null);

        TaskPreviewEntity preview = buildPreview("t6", "ready", "c6");
        preview.setExpireAt(LocalDateTime.now().minusHours(1));

        when(taskPreviewRepository.findByStatusAndExpireAtBefore(eq("ready"), any(LocalDateTime.class)))
                .thenReturn(List.of(preview));

        previewLifecycleService.recycleExpiredPreviews();

        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("expired");
    }

    private long idSeq = 1L;

    private TaskPreviewEntity buildPreview(String taskId, String status, String runtimeId) {
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setId(idSeq++);
        preview.setTaskId(taskId);
        preview.setProjectId("p1");
        preview.setUserId(1L);
        preview.setStatus(status);
        preview.setRuntimeId(runtimeId);
        preview.setCreatedAt(LocalDateTime.now().minusHours(2));
        preview.setUpdatedAt(LocalDateTime.now().minusHours(1));
        return preview;
    }
}
