package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.PreviewStatusCallback;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.service.PreviewGatewayService;
import com.smartark.gateway.service.PreviewSseRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.time.LocalDateTime;

@RestController
@RequestMapping({"/internal/preview", "/api/internal/preview"})
@Tag(name = "Internal Preview", description = "Internal preview callback APIs")
public class InternalPreviewController {
    private static final Logger logger = LoggerFactory.getLogger(InternalPreviewController.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final PreviewSseRegistry previewSseRegistry;
    @Autowired(required = false)
    private TaskRepository taskRepository;
    @Autowired(required = false)
    private TaskLogRepository taskLogRepository;
    @Autowired(required = false)
    private PreviewGatewayService previewGatewayService;

    @Value("${smartark.preview.internal-token:smartark-internal}")
    private String internalToken;
    @Value("${smartark.preview.default-ttl-hours:24}")
    private int previewDefaultTtlHours;
    @Value("${smartark.preview.gateway.enabled:false}")
    private boolean previewGatewayEnabled;

    public InternalPreviewController(TaskPreviewRepository taskPreviewRepository,
                                     PreviewSseRegistry previewSseRegistry) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.previewSseRegistry = previewSseRegistry;
    }

    @PostMapping("/{taskId}/status")
    @Operation(summary = "Update preview status callback", description = "Internal callback endpoint used by preview workers.")
    public ApiResponse<Void> updateStatus(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @Parameter(description = "Internal callback token", required = false)
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

    @PostMapping("/{taskId}/sandbox-ready")
    @Operation(summary = "Register sandbox as preview target", description = "Internal callback when deepagent sandbox is ready.")
    public ApiResponse<Map<String, String>> sandboxReady(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody SandboxReadyCallback callback) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        if (callback == null || callback.hostPort() == null || callback.hostPort() <= 0) {
            return ApiResponse.fail(400, "host_port is required");
        }
        if (taskRepository == null) {
            return ApiResponse.fail(500, "TaskRepository not available");
        }

        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "Task not found");
        }

        LocalDateTime now = LocalDateTime.now();
        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId).orElseGet(() -> {
            TaskPreviewEntity entity = new TaskPreviewEntity();
            entity.setTaskId(task.getId());
            entity.setProjectId(task.getProjectId());
            entity.setUserId(task.getUserId());
            entity.setCreatedAt(now);
            return entity;
        });

        LocalDateTime expireAt = now.plusHours(Math.max(previewDefaultTtlHours, 1));
        String previewUrl;
        if (previewGatewayEnabled && previewGatewayService != null) {
            previewUrl = previewGatewayService.registerRoute(taskId, callback.hostPort(), expireAt);
        } else {
            previewUrl = "/api/preview/" + taskId + "/";
        }

        preview.setStatus("ready");
        preview.setPhase(null);
        preview.setRuntimeId(callback.containerId());
        preview.setHostPort(callback.hostPort());
        preview.setPreviewUrl(previewUrl);
        preview.setExpireAt(expireAt);
        preview.setLastError(null);
        preview.setLastErrorCode(null);
        preview.setUpdatedAt(now);
        taskPreviewRepository.save(preview);

        task.setResultUrl(previewUrl);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        TaskPreviewResult result = new TaskPreviewResult(
                taskId, preview.getStatus(), preview.getPhase(),
                preview.getPreviewUrl(),
                preview.getExpireAt() == null ? null : preview.getExpireAt().toString(),
                preview.getLastError(), preview.getLastErrorCode(),
                preview.getBuildLogUrl()
        );
        previewSseRegistry.broadcast(taskId, result);
        appendTaskLog(taskId, "info", "Sandbox preview ready, hostPort=" + callback.hostPort() + ", url=" + previewUrl);
        return ApiResponse.success(Map.of("preview_url", previewUrl));
    }

    @PostMapping("/{taskId}/hotfix")
    @Operation(summary = "Record preview hotfix", description = "Internal callback for deepagent hotfix actions.")
    public ApiResponse<Void> hotfix(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody HotfixCallback callback) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        String description = callback == null || callback.fixDescription() == null ? "" : callback.fixDescription();
        appendTaskLog(taskId, "info", "Preview hotfix: " + description);
        return ApiResponse.success(null);
    }

    private void appendTaskLog(String taskId, String level, String content) {
        if (taskLogRepository == null) {
            return;
        }
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    public record SandboxReadyCallback(
            @com.fasterxml.jackson.annotation.JsonProperty("host_port") Integer hostPort,
            @com.fasterxml.jackson.annotation.JsonProperty("container_id") String containerId
    ) {
    }

    public record HotfixCallback(
            @com.fasterxml.jackson.annotation.JsonProperty("fix_description") String fixDescription,
            @com.fasterxml.jackson.annotation.JsonProperty("files_changed") java.util.List<String> filesChanged
    ) {
    }
}
