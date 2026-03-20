package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PreviewLifecycleService {
    private static final Logger logger = LoggerFactory.getLogger(PreviewLifecycleService.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final TaskLogRepository taskLogRepository;

    @Value("${smartark.preview.enabled:false}")
    private boolean previewEnabled;

    public PreviewLifecycleService(TaskPreviewRepository taskPreviewRepository,
                                   TaskLogRepository taskLogRepository) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.taskLogRepository = taskLogRepository;
    }

    @Scheduled(fixedDelayString = "${smartark.preview.recycle-interval-ms:60000}")
    public void recycleExpiredPreviews() {
        if (!previewEnabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<TaskPreviewEntity> expired = taskPreviewRepository.findByStatusAndExpireAtBefore("ready", now);
        for (TaskPreviewEntity preview : expired) {
            try {
                preview.setStatus("expired");
                preview.setRuntimeId(null);
                preview.setUpdatedAt(now);
                taskPreviewRepository.save(preview);
                appendTaskLog(preview.getTaskId(), "info", "Preview instance recycled as expired");
            } catch (Exception e) {
                logger.warn("Failed to recycle preview for task {}", preview.getTaskId(), e);
                appendTaskLog(preview.getTaskId(), "error", "Preview recycle failed: " + safeMessage(e));
            }
        }
    }

    private void appendTaskLog(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
