package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class PreviewGatewayService {
    private static final Logger logger = LoggerFactory.getLogger(PreviewGatewayService.class);

    private final PreviewRouteRegistry previewRouteRegistry;
    private final TaskPreviewRepository taskPreviewRepository;
    private final TaskLogRepository taskLogRepository;

    @Value("${smartark.preview.gateway.enabled:false}")
    private boolean previewGatewayEnabled;

    public PreviewGatewayService(PreviewRouteRegistry previewRouteRegistry,
                                 TaskPreviewRepository taskPreviewRepository,
                                 TaskLogRepository taskLogRepository) {
        this.previewRouteRegistry = previewRouteRegistry;
        this.taskPreviewRepository = taskPreviewRepository;
        this.taskLogRepository = taskLogRepository;
    }

    public String registerRoute(String taskId, Integer hostPort, LocalDateTime expireAt) {
        if (!previewGatewayEnabled || hostPort == null || hostPort <= 0) {
            return buildLegacyPreviewUrl(taskId);
        }
        String upstream = "http://localhost:" + hostPort;
        previewRouteRegistry.register(taskId, upstream, expireAt);
        appendTaskLog(taskId, "info", "Preview gateway route registered: " + taskId + " -> " + upstream);
        return buildGatewayPreviewUrl(taskId);
    }

    public void unregisterRoute(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        previewRouteRegistry.unregister(taskId);
        appendTaskLog(taskId, "info", "Preview gateway route unregistered: " + taskId);
    }

    public Optional<PreviewRouteRegistry.RouteEntry> resolveRoute(String taskId) {
        if (!previewGatewayEnabled) {
            return Optional.empty();
        }
        Optional<PreviewRouteRegistry.RouteEntry> fromRegistry = previewRouteRegistry.resolve(taskId);
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }
        Optional<TaskPreviewEntity> preview = taskPreviewRepository.findByTaskId(taskId);
        if (preview.isEmpty()) {
            return Optional.empty();
        }
        TaskPreviewEntity entity = preview.get();
        if (!"ready".equals(entity.getStatus()) || entity.getHostPort() == null || entity.getHostPort() <= 0) {
            return Optional.empty();
        }
        String upstream = "http://localhost:" + entity.getHostPort();
        previewRouteRegistry.register(taskId, upstream, entity.getExpireAt());
        logger.info("preview_gateway route restored taskId={} upstream={}", taskId, upstream);
        return previewRouteRegistry.resolve(taskId);
    }

    public int recycleExpiredRoutes() {
        if (!previewGatewayEnabled) {
            return 0;
        }
        int removed = previewRouteRegistry.recycleExpired(LocalDateTime.now());
        if (removed > 0) {
            logger.info("preview_gateway recycle expired routes removed={}", removed);
        }
        return removed;
    }

    public String buildGatewayPreviewUrl(String taskId) {
        return "/p/" + taskId + "/";
    }

    public String buildLegacyPreviewUrl(String taskId) {
        return "/api/preview/" + taskId + "/";
    }

    private void appendTaskLog(String taskId, String level, String content) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }
}
