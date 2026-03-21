package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
<<<<<<< HEAD
=======
import org.springframework.beans.factory.annotation.Autowired;
>>>>>>> origin/master
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

<<<<<<< HEAD
=======
    @Autowired(required = false)
    private ContainerRuntimeService containerRuntimeService;

>>>>>>> origin/master
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
<<<<<<< HEAD
                preview.setStatus("expired");
                preview.setRuntimeId(null);
=======
                // Stop and remove the container if runtime is available
                stopContainer(preview);

                preview.setStatus("expired");
                preview.setPhase(null);
>>>>>>> origin/master
                preview.setUpdatedAt(now);
                taskPreviewRepository.save(preview);
                appendTaskLog(preview.getTaskId(), "info", "Preview instance recycled as expired");
            } catch (Exception e) {
                logger.warn("Failed to recycle preview for task {}", preview.getTaskId(), e);
                appendTaskLog(preview.getTaskId(), "error", "Preview recycle failed: " + safeMessage(e));
            }
        }
    }

<<<<<<< HEAD
=======
    /**
     * Stop container associated with a preview. Idempotent.
     */
    private void stopContainer(TaskPreviewEntity preview) {
        String runtimeId = preview.getRuntimeId();
        if (runtimeId == null || runtimeId.isBlank()) {
            return;
        }
        if (containerRuntimeService == null) {
            logger.debug("ContainerRuntimeService not available, skipping container cleanup for {}", runtimeId);
            return;
        }
        try {
            containerRuntimeService.stopAndRemoveContainer(runtimeId);
            logger.info("Container stopped and removed for task {}: {}", preview.getTaskId(), runtimeId);
        } catch (Exception e) {
            logger.warn("Failed to stop container {} for task {}: {}", runtimeId, preview.getTaskId(), e.getMessage());
        }
    }

>>>>>>> origin/master
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
