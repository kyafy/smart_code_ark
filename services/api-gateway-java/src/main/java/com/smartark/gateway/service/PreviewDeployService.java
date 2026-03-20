package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PreviewDeployService {
    private static final Logger logger = LoggerFactory.getLogger(PreviewDeployService.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;

    @Value("${smartark.preview.enabled:false}")
    private boolean previewEnabled;
    @Value("${smartark.preview.auto-deploy-on-finish:true}")
    private boolean autoDeployOnFinish;
    @Value("${smartark.preview.default-ttl-hours:24}")
    private int previewDefaultTtlHours;
    @Value("${smartark.preview.max-concurrent-per-user:2}")
    private int previewMaxConcurrentPerUser;

    public PreviewDeployService(TaskPreviewRepository taskPreviewRepository,
                                TaskRepository taskRepository,
                                TaskLogRepository taskLogRepository) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
    }

    @Async
    public void deployPreviewAsync(String taskId) {
        long startedAt = System.currentTimeMillis();
        try {
            Optional<TaskEntity> taskOptional = taskRepository.findById(taskId);
            if (taskOptional.isEmpty()) {
                return;
            }
            TaskEntity task = taskOptional.get();
            if (!shouldTriggerDeploy(task)) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                    .orElseGet(() -> {
                        TaskPreviewEntity entity = new TaskPreviewEntity();
                        entity.setTaskId(task.getId());
                        entity.setProjectId(task.getProjectId());
                        entity.setUserId(task.getUserId());
                        entity.setCreatedAt(now);
                        return entity;
                    });
            if (isUserPreviewQuotaExceeded(task, preview)) {
                preview.setStatus("failed");
                preview.setPreviewUrl(null);
                preview.setExpireAt(null);
                preview.setLastError("[" + ErrorCodes.PREVIEW_CONCURRENCY_LIMIT + "] 预览并发数已达上限(" + Math.max(previewMaxConcurrentPerUser, 1) + ")");
                preview.setUpdatedAt(now);
                taskPreviewRepository.save(preview);
                appendTaskLog(taskId, "warn", "Preview deployment skipped: user concurrency limit reached");
                return;
            }
            preview.setStatus("provisioning");
            preview.setPreviewUrl(null);
            preview.setRuntimeId(UUID.randomUUID().toString().replace("-", ""));
            preview.setExpireAt(null);
            preview.setLastError(null);
            preview.setUpdatedAt(now);
            taskPreviewRepository.save(preview);

            appendTaskLog(taskId, "info", "Preview deployment started");

            String previewUrl = buildStaticPreviewUrl(taskId);
            LocalDateTime readyAt = LocalDateTime.now();
            LocalDateTime expireAt = readyAt.plusHours(Math.max(previewDefaultTtlHours, 1));
            preview.setStatus("ready");
            preview.setPreviewUrl(previewUrl);
            preview.setExpireAt(expireAt);
            preview.setLastError(null);
            preview.setUpdatedAt(readyAt);
            taskPreviewRepository.save(preview);

            task.setResultUrl(previewUrl);
            task.setUpdatedAt(readyAt);
            taskRepository.save(task);

            long duration = System.currentTimeMillis() - startedAt;
            appendTaskLog(taskId, "info", "Preview deployment succeeded in " + duration + "ms");
        } catch (Exception e) {
            logger.error("Preview deployment failed for task {}", taskId, e);
            int errorCode = resolvePreviewErrorCode(e);
            LocalDateTime failedAt = LocalDateTime.now();
            taskPreviewRepository.findByTaskId(taskId).ifPresent(preview -> {
                preview.setStatus("failed");
                preview.setLastError("[" + errorCode + "] " + messageOf(e));
                preview.setUpdatedAt(failedAt);
                taskPreviewRepository.save(preview);
            });
            long duration = System.currentTimeMillis() - startedAt;
            appendTaskLog(taskId, "error", "Preview deployment failed in " + duration + "ms, code=" + errorCode + ": " + messageOf(e));
        }
    }

    private boolean shouldTriggerDeploy(TaskEntity task) {
        return previewEnabled
                && autoDeployOnFinish
                && "finished".equals(task.getStatus())
                && ("generate".equals(task.getTaskType()) || "modify".equals(task.getTaskType()));
    }

    private String buildStaticPreviewUrl(String taskId) {
        return "http://localhost:5173/preview/" + taskId;
    }

    private void appendTaskLog(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    private String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private int resolvePreviewErrorCode(Throwable throwable) {
        String name = throwable.getClass().getSimpleName().toLowerCase();
        String message = messageOf(throwable).toLowerCase();
        if (name.contains("timeout") || message.contains("timeout") || message.contains("超时")) {
            return ErrorCodes.PREVIEW_TIMEOUT;
        }
        if (message.contains("proxy") || message.contains("代理")) {
            return ErrorCodes.PREVIEW_PROXY_FAILED;
        }
        if (message.contains("start") || message.contains("启动")) {
            return ErrorCodes.PREVIEW_START_FAILED;
        }
        return ErrorCodes.PREVIEW_BUILD_FAILED;
    }

    private boolean isUserPreviewQuotaExceeded(TaskEntity task, TaskPreviewEntity currentPreview) {
        long activeCount = taskPreviewRepository.countByUserIdAndStatusIn(
                task.getUserId(),
                List.of("provisioning", "ready")
        );
        if (currentPreview.getId() != null && ("provisioning".equals(currentPreview.getStatus()) || "ready".equals(currentPreview.getStatus()))) {
            activeCount = Math.max(0, activeCount - 1);
        }
        return activeCount >= Math.max(previewMaxConcurrentPerUser, 1);
    }
}
