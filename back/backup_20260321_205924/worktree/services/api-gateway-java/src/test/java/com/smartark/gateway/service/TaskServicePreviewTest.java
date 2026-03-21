package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.db.repo.PaperCorpusChunkRepository;
import com.smartark.gateway.db.repo.PaperCorpusDocRepository;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.dto.PreviewLogsResult;
import com.smartark.gateway.dto.TaskPreviewResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServicePreviewTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskStepRepository taskStepRepository;
    @Mock private TaskLogRepository taskLogRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ArtifactRepository artifactRepository;
    @Mock private TaskPreviewRepository taskPreviewRepository;
    @Mock private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock private PaperCorpusDocRepository paperCorpusDocRepository;
    @Mock private PaperCorpusChunkRepository paperCorpusChunkRepository;
    @Mock private TaskExecutorService taskExecutorService;
    @Mock private PreviewDeployService previewDeployService;
    @Mock private BillingService billingService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository, taskStepRepository, taskLogRepository,
                projectRepository, artifactRepository, taskPreviewRepository,
                paperTopicSessionRepository, paperOutlineVersionRepository,
                paperCorpusDocRepository, paperCorpusChunkRepository,
                taskExecutorService, previewDeployService, billingService,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(taskService, "previewMaxConcurrentPerUser", 2);
        ReflectionTestUtils.setField(taskService, "previewLogDir", "/tmp/smartark/preview-logs");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ===== getPreview Tests =====

    @Test
    void getPreview_shouldReturnPreviewWithPhaseField() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t1", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t1", "provisioning", "install_deps");

        when(taskRepository.findById("t1")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t1")).thenReturn(Optional.of(preview));

        TaskPreviewResult result = taskService.getPreview("t1");

        assertThat(result.taskId()).isEqualTo("t1");
        assertThat(result.status()).isEqualTo("provisioning");
        assertThat(result.phase()).isEqualTo("install_deps");
        assertThat(result.previewUrl()).isNull();
        assertThat(result.lastError()).isNull();
        assertThat(result.lastErrorCode()).isNull();
    }

    @Test
    void getPreview_shouldReturnReadyPreviewWithAllFields() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t2", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t2", "ready", null);
        preview.setPreviewUrl("http://localhost:30001");
        preview.setExpireAt(LocalDateTime.of(2026, 3, 22, 12, 0));
        preview.setBuildLogUrl("file:///tmp/smartark/preview-logs/t2-install.log");

        when(taskRepository.findById("t2")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t2")).thenReturn(Optional.of(preview));

        TaskPreviewResult result = taskService.getPreview("t2");

        assertThat(result.status()).isEqualTo("ready");
        assertThat(result.phase()).isNull();
        assertThat(result.previewUrl()).isEqualTo("http://localhost:30001");
        assertThat(result.expireAt()).isNotNull();
        assertThat(result.buildLogUrl()).contains("t2-install.log");
    }

    @Test
    void getPreview_shouldReturnFailedPreviewWithErrorCode() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t3", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t3", "failed", "install_deps");
        preview.setLastError("npm install failed");
        preview.setLastErrorCode(3101);

        when(taskRepository.findById("t3")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t3")).thenReturn(Optional.of(preview));

        TaskPreviewResult result = taskService.getPreview("t3");

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.lastError()).isEqualTo("npm install failed");
        assertThat(result.lastErrorCode()).isEqualTo(3101);
    }

    @Test
    void getPreview_shouldReturnFallbackForNonPreviewTask() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t4", "paper_outline", 1L);

        when(taskRepository.findById("t4")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t4")).thenReturn(Optional.empty());

        TaskPreviewResult result = taskService.getPreview("t4");

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.lastError()).contains("不支持预览");
    }

    @Test
    void getPreview_shouldThrowForUnauthorizedUser() {
        RequestContext.setUserId("999");
        TaskEntity task = buildTask("t5", "generate", 1L);

        when(taskRepository.findById("t5")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getPreview("t5"))
                .isInstanceOf(BusinessException.class);
    }

    // ===== rebuildPreview Tests =====

    @Test
    void rebuildPreview_shouldResetPhaseAndErrorCode() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t6", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t6", "failed", "health_check");
        preview.setLastError("timeout");
        preview.setLastErrorCode(3104);

        when(taskRepository.findById("t6")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t6")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TaskPreviewResult result = taskService.rebuildPreview("t6");

        assertThat(result.status()).isEqualTo("provisioning");
        assertThat(result.phase()).isNull();
        assertThat(result.lastError()).isNull();
        assertThat(result.lastErrorCode()).isNull();

        // Verify deploy triggered
        verify(previewDeployService).deployPreviewAsync("t6");
    }

    @Test
    void rebuildPreview_shouldAllowRebuildFromExpired() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t7", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t7", "expired", null);

        when(taskRepository.findById("t7")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t7")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(taskLogRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TaskPreviewResult result = taskService.rebuildPreview("t7");

        assertThat(result.status()).isEqualTo("provisioning");
        verify(previewDeployService).deployPreviewAsync("t7");
    }

    @Test
    void rebuildPreview_shouldRejectWhenStatusIsReady() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t8", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t8", "ready", null);

        when(taskRepository.findById("t8")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t8")).thenReturn(Optional.of(preview));

        assertThatThrownBy(() -> taskService.rebuildPreview("t8"))
                .isInstanceOf(BusinessException.class);

        verify(previewDeployService, never()).deployPreviewAsync(any());
    }

    @Test
    void rebuildPreview_shouldRejectWhenConcurrencyLimitReached() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t9", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t9", "failed", null);

        when(taskRepository.findById("t9")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t9")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.countByUserIdAndStatusIn(eq(1L), any())).thenReturn(2L);

        assertThatThrownBy(() -> taskService.rebuildPreview("t9"))
                .isInstanceOf(BusinessException.class);
    }

    // ===== getPreviewLogs Tests =====

    @Test
    void getPreviewLogs_shouldReturnTaskLogsAsFallback() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t10", "generate", 1L);
        TaskPreviewEntity preview = buildPreview("t10", "failed", null);

        when(taskRepository.findById("t10")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t10")).thenReturn(Optional.of(preview));

        TaskLogEntity log1 = new TaskLogEntity();
        log1.setTaskId("t10");
        log1.setLevel("info");
        log1.setContent("Preview deployment started");
        log1.setCreatedAt(LocalDateTime.now());

        TaskLogEntity log2 = new TaskLogEntity();
        log2.setTaskId("t10");
        log2.setLevel("error");
        log2.setContent("Preview deployment failed");
        log2.setCreatedAt(LocalDateTime.now());

        when(taskLogRepository.findByTaskIdOrderByCreatedAtAsc("t10")).thenReturn(List.of(log1, log2));

        PreviewLogsResult result = taskService.getPreviewLogs("t10", 200);

        assertThat(result.taskId()).isEqualTo("t10");
        assertThat(result.logs()).hasSize(2);
        assertThat(result.logs().get(0).message()).contains("Preview deployment started");
        assertThat(result.logs().get(1).message()).contains("Preview deployment failed");
    }

    @Test
    void getPreviewLogs_shouldRespectTailLimit() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t11", "generate", 1L);

        when(taskRepository.findById("t11")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t11")).thenReturn(Optional.empty());

        // Create many log entries
        List<TaskLogEntity> manyLogs = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            TaskLogEntity log = new TaskLogEntity();
            log.setTaskId("t11");
            log.setLevel("info");
            log.setContent("Preview log line " + i);
            log.setCreatedAt(LocalDateTime.now());
            manyLogs.add(log);
        }
        when(taskLogRepository.findByTaskIdOrderByCreatedAtAsc("t11")).thenReturn(manyLogs);

        PreviewLogsResult result = taskService.getPreviewLogs("t11", 3);

        assertThat(result.logs()).hasSize(3);
        // Should return the last 3 lines
        assertThat(result.logs().get(0).message()).contains("Preview log line 7");
        assertThat(result.logs().get(2).message()).contains("Preview log line 9");
    }

    @Test
    void getPreviewLogs_shouldThrowForUnauthorizedUser() {
        RequestContext.setUserId("999");
        TaskEntity task = buildTask("t12", "generate", 1L);

        when(taskRepository.findById("t12")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getPreviewLogs("t12", 200))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getPreviewLogs_shouldReturnEmptyWhenNoLogs() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("t13", "generate", 1L);

        when(taskRepository.findById("t13")).thenReturn(Optional.of(task));
        when(taskPreviewRepository.findByTaskId("t13")).thenReturn(Optional.empty());
        when(taskLogRepository.findByTaskIdOrderByCreatedAtAsc("t13")).thenReturn(List.of());

        PreviewLogsResult result = taskService.getPreviewLogs("t13", 200);

        assertThat(result.taskId()).isEqualTo("t13");
        assertThat(result.logs()).isEmpty();
    }

    // ===== Helper Methods =====

    private TaskEntity buildTask(String taskId, String taskType, Long userId) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId("p1");
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setStatus("finished");
        return task;
    }

    private TaskPreviewEntity buildPreview(String taskId, String status, String phase) {
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setId(1L);
        preview.setTaskId(taskId);
        preview.setProjectId("p1");
        preview.setUserId(1L);
        preview.setStatus(status);
        preview.setPhase(phase);
        preview.setCreatedAt(LocalDateTime.now());
        preview.setUpdatedAt(LocalDateTime.now());
        return preview;
    }
}
