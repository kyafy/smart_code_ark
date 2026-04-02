package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.RuntimeSmokeTestReportResult;
import com.smartark.gateway.dto.TaskPreviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PreviewDeployService {
    private static final Logger logger = LoggerFactory.getLogger(PreviewDeployService.class);
    private static final String RUNTIME_SMOKE_TEST_REPORT_FILE = "runtime_smoke_test_report.json";

    // Phase constants
    public static final String PHASE_PREPARE_ARTIFACT = "prepare_artifact";
    public static final String PHASE_START_RUNTIME = "start_runtime";
    public static final String PHASE_INSTALL_DEPS = "install_deps";
    public static final String PHASE_BOOT_SERVICE = "boot_service";
    public static final String PHASE_HEALTH_CHECK = "health_check";
    public static final String PHASE_PUBLISH_GATEWAY = "publish_gateway";

    private final TaskPreviewRepository taskPreviewRepository;
    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;

    @Autowired(required = false)
    private ContainerRuntimeService containerRuntimeService;
    @Autowired(required = false)
    private PreviewGatewayService previewGatewayService;
    @Autowired(required = false)
    private FrontendRuntimePlanService frontendRuntimePlanService;
    @Autowired(required = false)
    private ObjectMapper objectMapper;
    @Autowired(required = false)
    private TaskExecutionModeResolver taskExecutionModeResolver;

    @Autowired
    private PreviewSseRegistry previewSseRegistry;

    @Value("${smartark.preview.enabled:false}")
    private boolean previewEnabled;
    @Value("${smartark.preview.auto-deploy-on-finish:true}")
    private boolean autoDeployOnFinish;
    @Value("${smartark.preview.default-ttl-hours:24}")
    private int previewDefaultTtlHours;
    @Value("${smartark.preview.max-concurrent-per-user:2}")
    private int previewMaxConcurrentPerUser;
    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;
    @Value("${smartark.preview.health-check-timeout-seconds:60}")
    private int healthCheckTimeoutSeconds;
    @Value("${smartark.preview.health-check-interval-ms:3000}")
    private int healthCheckIntervalMs;
    @Value("${smartark.preview.log-dir:/tmp/smartark/preview-logs}")
    private String previewLogDir;
    @Value("${smartark.preview.gateway.enabled:false}")
    private boolean previewGatewayEnabled;
    @Value("${smartark.agent.preview.skip-when-deepagent:true}")
    private boolean skipPreviewWhenDeepAgent;

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
        TaskPreviewEntity preview = null;
        try {
            Optional<TaskEntity> taskOptional = taskRepository.findById(taskId);
            if (taskOptional.isEmpty()) {
                return;
            }
            TaskEntity task = taskOptional.get();
            if (shouldSkipBecauseDeepAgent(task)) {
                appendTaskLog(taskId, "info", "Preview deployment skipped: deepagent mode selected");
                return;
            }
            if (!shouldTriggerDeploy(task)) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            preview = taskPreviewRepository.findByTaskId(taskId)
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
                preview.setPhase(null);
                preview.setPreviewUrl(null);
                preview.setExpireAt(null);
                preview.setLastError("预览并发数已达上限(" + Math.max(previewMaxConcurrentPerUser, 1) + ")");
                preview.setLastErrorCode(ErrorCodes.PREVIEW_CONCURRENCY_LIMIT);
                preview.setUpdatedAt(now);
                taskPreviewRepository.save(preview);
                appendTaskLog(taskId, "warn", "Preview deployment skipped: user concurrency limit reached");
                return;
            }

            // Reset to provisioning
            if (previewGatewayService != null) {
                previewGatewayService.unregisterRoute(taskId);
            }
            preview.setStatus("provisioning");
            preview.setPhase(null);
            preview.setPreviewUrl(null);
            preview.setRuntimeId(null);
            preview.setBuildLogUrl(null);
            preview.setExpireAt(null);
            preview.setLastError(null);
            preview.setLastErrorCode(null);
            preview.setUpdatedAt(now);
            taskPreviewRepository.save(preview);
            appendTaskLog(taskId, "info", "Preview deployment started");

            // If container runtime is not available, fallback to static URL
            if (containerRuntimeService == null) {
                logger.info("ContainerRuntimeService not available, falling back to static preview for task {}", taskId);
                deployStaticFallback(taskId, task, preview, startedAt);
                return;
            }

            Path workspacePath = resolveWorkspacePath(taskId);
            ReusableRuntimeCandidate reusableRuntime = tryLoadReusableRuntime(taskId, workspacePath);
            if (reusableRuntime != null && containerRuntimeService.isContainerRunning(reusableRuntime.runtimeId())) {
                appendTaskLog(taskId, "info", "Preview deployment reusing runtime smoke container: " + reusableRuntime.runtimeId());
                publishPreviewFromExistingRuntime(taskId, task, preview, reusableRuntime, startedAt);
                return;
            } else if (reusableRuntime != null) {
                appendTaskLog(taskId, "warn", "Reusable runtime from runtime_smoke_test is unavailable, falling back to fresh preview deploy");
                stopReusableRuntime(reusableRuntime.runtimeId());
            }

            // ===== Phase 1: prepare_artifact =====
            updatePhase(preview, PHASE_PREPARE_ARTIFACT);
            appendTaskLog(taskId, "info", "Phase: prepare_artifact - locating frontend artifact");

            FrontendRuntimePlanService.FrontendRuntimePlan runtimePlan = resolveRuntimePlan(task, workspacePath);
            if (runtimePlan == null) {
                throw new RuntimeException("Frontend artifact not found in workspace: " + workspacePath);
            }
            appendTaskLog(taskId, "info", "Frontend artifact found: " + runtimePlan.projectDir());

            // ===== Phase 2: start_runtime =====
            updatePhase(preview, PHASE_START_RUNTIME);
            appendTaskLog(taskId, "info", "Phase: start_runtime - creating Docker container");

            int hostPort = containerRuntimeService.findAvailablePort();
            String containerId = containerRuntimeService.createAndStartContainer(
                    runtimePlan.projectDir().toAbsolutePath().toString(),
                    hostPort,
                    taskId
            );
            preview.setRuntimeId(containerId);
            preview.setHostPort(hostPort);
            preview.setUpdatedAt(LocalDateTime.now());
            taskPreviewRepository.save(preview);
            appendTaskLog(taskId, "info", "Container started: " + containerId + " on port " + hostPort);

            // ===== Phase 3: install_deps =====
            updatePhase(preview, PHASE_INSTALL_DEPS);
            appendTaskLog(taskId, "info", "Phase: install_deps - running npm install");

            ContainerRuntimeService.ExecResult installResult =
                    containerRuntimeService.execInContainer(
                            containerId,
                            "sh",
                            "-c",
                            runtimeInstallCommand()
                    );

            // Save build log
            saveBuildLog(taskId, "install", installResult.output());
            preview.setBuildLogUrl("file://" + previewLogDir + "/" + taskId + "-install.log");
            preview.setUpdatedAt(LocalDateTime.now());
            taskPreviewRepository.save(preview);

            if (!installResult.isSuccess()) {
                throw new RuntimeException("npm install failed (exit=" + installResult.exitCode() + "): "
                        + truncate(installResult.output(), 500));
            }
            appendTaskLog(taskId, "info", "npm install completed successfully");

            for (String preStartScript : runtimePlan.preStartScripts()) {
                appendTaskLog(taskId, "info", "Phase: install_deps - running prestart script " + preStartScript);
                ContainerRuntimeService.ExecResult preStartResult = containerRuntimeService.execInContainer(
                        containerId,
                        "sh",
                        "-c",
                        runtimeNpmRunCommand(preStartScript)
                );
                saveBuildLog(taskId, "prestart-" + sanitizeForLog(preStartScript), preStartResult.output());
                if (!preStartResult.isSuccess()) {
                    throw new RuntimeException(preStartScript + " failed (exit=" + preStartResult.exitCode() + "): "
                            + truncate(preStartResult.output(), 500));
                }
            }

            // ===== Phase 4: boot_service =====
            updatePhase(preview, PHASE_BOOT_SERVICE);
            appendTaskLog(taskId, "info", "Phase: boot_service - starting script " + runtimePlan.startScript());

            // Start dev server in background (detached)
            containerRuntimeService.execDetached(containerId, "sh", "-c",
                    runtimeBootCommand(runtimePlan.startScript(), "/tmp/dev-server.log"));

            appendTaskLog(taskId, "info", "Dev server process started");

            // ===== Phase 5: health_check =====
            updatePhase(preview, PHASE_HEALTH_CHECK);
            appendTaskLog(taskId, "info", "Phase: health_check - waiting for service to be ready");

            boolean healthy = containerRuntimeService.checkHealth("localhost", hostPort,
                    healthCheckTimeoutSeconds, healthCheckIntervalMs);

            preview.setLastHealthCheckAt(LocalDateTime.now());
            preview.setUpdatedAt(LocalDateTime.now());
            taskPreviewRepository.save(preview);

            if (!healthy) {
                // Capture container logs for diagnostics
                String containerLogs = containerRuntimeService.getContainerLogs(containerId, 50);
                saveBuildLog(taskId, "boot", containerLogs);
                throw new RuntimeException("Health check timed out after " + healthCheckTimeoutSeconds + "s");
            }
            appendTaskLog(taskId, "info", "Health check passed");

            // ===== Phase 6: publish_gateway =====
            updatePhase(preview, PHASE_PUBLISH_GATEWAY);
            appendTaskLog(taskId, "info", "Phase: publish_gateway - publishing preview URL");
            appendTaskLog(taskId, "info", "Preview gateway switch: enabled=" + previewGatewayEnabled);

            LocalDateTime readyAt = LocalDateTime.now();
            LocalDateTime expireAt = readyAt.plusHours(Math.max(previewDefaultTtlHours, 1));
            String previewUrl;
            if (previewGatewayEnabled && previewGatewayService != null) {
                previewUrl = previewGatewayService.registerRoute(taskId, hostPort, expireAt);
            } else {
                previewUrl = "/api/preview/" + taskId + "/";
            }
            preview.setStatus("ready");
            preview.setPhase(null);
            preview.setPreviewUrl(previewUrl);
            preview.setExpireAt(expireAt);
            preview.setLastError(null);
            preview.setLastErrorCode(null);
            preview.setUpdatedAt(readyAt);
            taskPreviewRepository.save(preview);
            broadcastStatus(preview);

            task.setResultUrl(previewUrl);
            task.setUpdatedAt(readyAt);
            taskRepository.save(task);

            long duration = System.currentTimeMillis() - startedAt;
            appendTaskLog(taskId, "info", "Preview deployment succeeded in " + duration + "ms, URL: " + previewUrl);
        } catch (Exception e) {
            logger.error("Preview deployment failed for task {}", taskId, e);
            if (previewGatewayService != null) {
                previewGatewayService.unregisterRoute(taskId);
            }
            int errorCode = resolvePreviewErrorCode(e);
            LocalDateTime failedAt = LocalDateTime.now();

            TaskPreviewEntity failedPreview = preview != null ? preview
                    : taskPreviewRepository.findByTaskId(taskId).orElse(null);
            if (failedPreview != null) {
                failedPreview.setStatus("failed");
                failedPreview.setLastError(truncate(messageOf(e), 1000));
                failedPreview.setLastErrorCode(errorCode);
                failedPreview.setUpdatedAt(failedAt);
                taskPreviewRepository.save(failedPreview);
                broadcastStatus(failedPreview);
            }

            long duration = System.currentTimeMillis() - startedAt;
            appendTaskLog(taskId, "error",
                    "Preview deployment failed in " + duration + "ms, code=" + errorCode + ": " + messageOf(e));
        }
    }

    /**
     * Fallback: generate static preview URL when Docker is not available.
     */
    private void deployStaticFallback(String taskId, TaskEntity task, TaskPreviewEntity preview, long startedAt) {
        String previewUrl = buildStaticPreviewUrl(taskId);
        LocalDateTime readyAt = LocalDateTime.now();
        LocalDateTime expireAt = readyAt.plusHours(Math.max(previewDefaultTtlHours, 1));
        preview.setStatus("ready");
        preview.setPhase(null);
        preview.setPreviewUrl(previewUrl);
        preview.setExpireAt(expireAt);
        preview.setLastError(null);
        preview.setLastErrorCode(null);
        preview.setRuntimeId(UUID.randomUUID().toString().replace("-", ""));
        preview.setUpdatedAt(readyAt);
        taskPreviewRepository.save(preview);

        task.setResultUrl(previewUrl);
        task.setUpdatedAt(readyAt);
        taskRepository.save(task);

        long duration = System.currentTimeMillis() - startedAt;
        appendTaskLog(taskId, "info", "Preview deployed (static fallback) in " + duration + "ms");
    }

    public void cleanupReusableRuntime(String taskId) {
        if (containerRuntimeService == null) {
            return;
        }
        Path workspacePath = resolveWorkspacePath(taskId);
        ReusableRuntimeCandidate reusableRuntime = tryLoadReusableRuntime(taskId, workspacePath);
        if (reusableRuntime == null) {
            return;
        }
        stopReusableRuntime(reusableRuntime.runtimeId());
        appendTaskLog(taskId, "info", "Reusable runtime from runtime_smoke_test cleaned up after task failure");
    }

    private boolean shouldTriggerDeploy(TaskEntity task) {
        return previewEnabled
                && autoDeployOnFinish
                && "finished".equals(task.getStatus())
                && ("generate".equals(task.getTaskType()) || "modify".equals(task.getTaskType()));
    }

    private boolean shouldSkipBecauseDeepAgent(TaskEntity task) {
        if (!skipPreviewWhenDeepAgent || taskExecutionModeResolver == null || task == null) {
            return false;
        }
        TaskExecutionModeResolver.TaskExecutionDecision decision =
                taskExecutionModeResolver.resolve(task.getId(), task.getTaskType());
        return decision.isDeepAgentSelected();
    }

    private String buildStaticPreviewUrl(String taskId) {
        return "http://localhost:5173/preview/" + taskId;
    }

    private void updatePhase(TaskPreviewEntity preview, String phase) {
        preview.setPhase(phase);
        preview.setUpdatedAt(LocalDateTime.now());
        taskPreviewRepository.save(preview);
        broadcastStatus(preview);
    }

    private void broadcastStatus(TaskPreviewEntity preview) {
        if (previewSseRegistry == null) return;
        TaskPreviewResult result = new TaskPreviewResult(
                preview.getTaskId(),
                preview.getStatus(),
                preview.getPhase(),
                preview.getPreviewUrl(),
                preview.getExpireAt() == null ? null : preview.getExpireAt().toString(),
                preview.getLastError(),
                preview.getLastErrorCode(),
                preview.getBuildLogUrl()
        );
        previewSseRegistry.broadcast(preview.getTaskId(), result);
    }

    private void publishPreviewFromExistingRuntime(
            String taskId,
            TaskEntity task,
            TaskPreviewEntity preview,
            ReusableRuntimeCandidate reusableRuntime,
            long startedAt
    ) {
        updatePhase(preview, PHASE_HEALTH_CHECK);
        appendTaskLog(taskId, "info", "Phase: health_check - validating reused runtime");

        preview.setRuntimeId(reusableRuntime.runtimeId());
        preview.setHostPort(reusableRuntime.hostPort());
        preview.setBuildLogUrl(reusableRuntime.buildLogUrl());
        preview.setUpdatedAt(LocalDateTime.now());
        taskPreviewRepository.save(preview);

        boolean healthy = containerRuntimeService.checkHealth(
                "localhost",
                reusableRuntime.hostPort(),
                healthCheckTimeoutSeconds,
                healthCheckIntervalMs
        );

        preview.setLastHealthCheckAt(LocalDateTime.now());
        preview.setUpdatedAt(LocalDateTime.now());
        taskPreviewRepository.save(preview);
        if (!healthy) {
            stopReusableRuntime(reusableRuntime.runtimeId());
            throw new RuntimeException("Reused runtime health check timed out after " + healthCheckTimeoutSeconds + "s");
        }

        appendTaskLog(taskId, "info", "Reused runtime health check passed");
        publishReadyPreview(taskId, task, preview, reusableRuntime.hostPort(), startedAt);
    }

    private void publishReadyPreview(
            String taskId,
            TaskEntity task,
            TaskPreviewEntity preview,
            int hostPort,
            long startedAt
    ) {
        updatePhase(preview, PHASE_PUBLISH_GATEWAY);
        appendTaskLog(taskId, "info", "Phase: publish_gateway - publishing preview URL");
        appendTaskLog(taskId, "info", "Preview gateway switch: enabled=" + previewGatewayEnabled);

        LocalDateTime readyAt = LocalDateTime.now();
        LocalDateTime expireAt = readyAt.plusHours(Math.max(previewDefaultTtlHours, 1));
        String previewUrl;
        if (previewGatewayEnabled && previewGatewayService != null) {
            previewUrl = previewGatewayService.registerRoute(taskId, hostPort, expireAt);
        } else {
            previewUrl = "/api/preview/" + taskId + "/";
        }
        preview.setStatus("ready");
        preview.setPhase(null);
        preview.setPreviewUrl(previewUrl);
        preview.setExpireAt(expireAt);
        preview.setLastError(null);
        preview.setLastErrorCode(null);
        preview.setUpdatedAt(readyAt);
        taskPreviewRepository.save(preview);
        broadcastStatus(preview);

        task.setResultUrl(previewUrl);
        task.setUpdatedAt(readyAt);
        taskRepository.save(task);

        long duration = System.currentTimeMillis() - startedAt;
        appendTaskLog(taskId, "info", "Preview deployment succeeded in " + duration + "ms, URL: " + previewUrl);
    }

    /**
     * Resolve the workspace directory for a task.
     */
    private Path resolveWorkspacePath(String taskId) {
        return Paths.get(workspaceRoot, taskId).toAbsolutePath();
    }

    private ReusableRuntimeCandidate tryLoadReusableRuntime(String taskId, Path workspacePath) {
        if (objectMapper == null) {
            return null;
        }
        Path reportPath = workspacePath.resolve(RUNTIME_SMOKE_TEST_REPORT_FILE);
        if (!Files.exists(reportPath)) {
            return null;
        }
        try {
            RuntimeSmokeTestReportResult report = objectMapper.readValue(
                    Files.readString(reportPath, StandardCharsets.UTF_8),
                    RuntimeSmokeTestReportResult.class
            );
            if (report.reusableRuntime() == null || !report.reusableRuntime().availableForPreview()) {
                return null;
            }
            if (report.reusableRuntime().runtimeId() == null
                    || report.reusableRuntime().runtimeId().isBlank()
                    || report.reusableRuntime().hostPort() == null) {
                return null;
            }
            String buildLogUrl = report.reusableRuntime().bootLogRef() == null
                    ? null
                    : "file://" + workspacePath.resolve(report.reusableRuntime().bootLogRef()).normalize();
            return new ReusableRuntimeCandidate(
                    report.reusableRuntime().runtimeId(),
                    report.reusableRuntime().hostPort(),
                    buildLogUrl
            );
        } catch (Exception e) {
            logger.warn("Failed to parse runtime smoke report for task {}: {}", taskId, e.getMessage());
            return null;
        }
    }

    private FrontendRuntimePlanService.FrontendRuntimePlan resolveRuntimePlan(TaskEntity task, Path workspacePath) {
        if (frontendRuntimePlanService != null) {
            Optional<FrontendRuntimePlanService.FrontendRuntimePlan> resolved = frontendRuntimePlanService.resolvePlan(task, workspacePath);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        Path fallbackProjectDir = resolveFrontendPathFallback(workspacePath);
        if (fallbackProjectDir == null) {
            return null;
        }
        return new FrontendRuntimePlanService.FrontendRuntimePlan(fallbackProjectDir, "dev", List.of());
    }

    /**
     * Try to locate the frontend project directory within the workspace.
     * Looks for common patterns: frontend/, client/, web/, or root with package.json.
     */
    private Path resolveFrontendPathFallback(Path workspace) {
        // Check common frontend directory names
        String[] candidates = {"frontend", "client", "web", "frontend-web", "app"};
        for (String candidate : candidates) {
            Path candidatePath = workspace.resolve(candidate);
            if (Files.exists(candidatePath.resolve("package.json"))) {
                return candidatePath.toAbsolutePath();
            }
        }
        // Check if workspace root itself is a frontend project
        if (Files.exists(workspace.resolve("package.json"))) {
            return workspace.toAbsolutePath();
        }
        return null;
    }

    private String runtimeInstallCommand() {
        return frontendRuntimePlanService == null
                ? "npm install --prefer-offline 2>&1"
                : frontendRuntimePlanService.installCommand();
    }

    private String runtimeNpmRunCommand(String script) {
        return frontendRuntimePlanService == null
                ? "npm run " + script + " 2>&1"
                : frontendRuntimePlanService.npmRunCommand(script);
    }

    private String runtimeBootCommand(String startScript, String logFile) {
        if (frontendRuntimePlanService == null) {
            return "npm run dev -- --host 0.0.0.0 > " + logFile + " 2>&1 || npm run preview -- --host 0.0.0.0 > " + logFile + " 2>&1";
        }
        return frontendRuntimePlanService.buildBootCommand(startScript, logFile);
    }

    private String sanitizeForLog(String value) {
        return value == null ? "script" : value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private void stopReusableRuntime(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank() || containerRuntimeService == null) {
            return;
        }
        try {
            containerRuntimeService.stopAndRemoveContainer(runtimeId);
        } catch (Exception e) {
            logger.warn("Failed to stop reusable runtime {}: {}", runtimeId, e.getMessage());
        }
    }

    private void saveBuildLog(String taskId, String phase, String content) {
        try {
            Path logDir = Paths.get(previewLogDir);
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve(taskId + "-" + phase + ".log");
            Files.writeString(logFile, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to save build log for task {}: {}", taskId, e.getMessage());
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

    private String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    private int resolvePreviewErrorCode(Throwable throwable) {
        String name = throwable.getClass().getSimpleName().toLowerCase();
        String message = messageOf(throwable).toLowerCase();
        if (name.contains("timeout") || message.contains("timeout") || message.contains("超时")
                || message.contains("health check timed out")) {
            return ErrorCodes.PREVIEW_TIMEOUT;
        }
        if (message.contains("proxy") || message.contains("代理")) {
            return ErrorCodes.PREVIEW_PROXY_FAILED;
        }
        if (message.contains("start") || message.contains("启动") || message.contains("container")) {
            return ErrorCodes.PREVIEW_START_FAILED;
        }
        if (message.contains("npm install") || message.contains("build")) {
            return ErrorCodes.PREVIEW_BUILD_FAILED;
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

    private record ReusableRuntimeCandidate(
            String runtimeId,
            Integer hostPort,
            String buildLogUrl
    ) {
    }
}
