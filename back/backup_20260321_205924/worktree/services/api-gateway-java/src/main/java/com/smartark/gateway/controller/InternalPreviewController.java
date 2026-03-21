package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.dto.PreviewStatusCallback;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.service.PreviewSseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/internal/preview")
public class InternalPreviewController {
    private static final Logger logger = LoggerFactory.getLogger(InternalPreviewController.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final PreviewSseRegistry previewSseRegistry;

    @Value("${smartark.preview.internal-token:smartark-internal}")
    private String internalToken;

    public InternalPreviewController(TaskPreviewRepository taskPreviewRepository,
                                     PreviewSseRegistry previewSseRegistry) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.previewSseRegistry = previewSseRegistry;
    }

    @PostMapping("/{taskId}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody PreviewStatusCallback callback) {

        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }

        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId).orElse(null);
        if (preview == null) {
            return ApiResponse.fail(404, "Preview record not found");
        }

        LocalDateTime now = LocalDateTime.now();
        if (callback.status() != null) {
            preview.setStatus(callback.status());
        }
        if (callback.phase() != null) {
            preview.setPhase(callback.phase());
        }
        if (callback.previewUrl() != null) {
            preview.setPreviewUrl(callback.previewUrl());
        }
        if (callback.error() != null) {
            preview.setLastError(callback.error());
        }
        if (callback.errorCode() != null) {
            preview.setLastErrorCode(callback.errorCode());
        }
        preview.setUpdatedAt(now);
        taskPreviewRepository.save(preview);

        // Broadcast via SSE
        TaskPreviewResult result = new TaskPreviewResult(
                taskId, preview.getStatus(), preview.getPhase(),
                preview.getPreviewUrl(),
                preview.getExpireAt() == null ? null : preview.getExpireAt().toString(),
                preview.getLastError(), preview.getLastErrorCode(),
                preview.getBuildLogUrl()
        );
        previewSseRegistry.broadcast(taskId, result);

        logger.info("Internal status update for task {}: status={}, phase={}", taskId, callback.status(), callback.phase());
        return ApiResponse.success(null);
    }
}
