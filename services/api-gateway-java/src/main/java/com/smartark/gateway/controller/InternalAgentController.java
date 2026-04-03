package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.dto.InternalTaskDispatchResult;
import com.smartark.gateway.dto.InternalTaskLogRequest;
import com.smartark.gateway.dto.InternalTaskStepUpdateRequest;
import com.smartark.gateway.dto.NodeMetricsPayload;
import com.smartark.gateway.service.TaskExecutionModeResolver;
import com.smartark.gateway.service.TaskExecutorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@RestController
@RequestMapping({"/internal/task", "/api/internal/task"})
@Tag(name = "Internal Agent", description = "Internal callbacks and task dispatch APIs for DeepAgent")
public class InternalAgentController {
    private static final Logger logger = LoggerFactory.getLogger(InternalAgentController.class);

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskExecutorService taskExecutorService;

    @Value("${smartark.agent.internal-token:smartark-internal}")
    private String internalToken;

    public InternalAgentController(TaskRepository taskRepository,
                                   TaskStepRepository taskStepRepository,
                                   TaskLogRepository taskLogRepository,
                                   TaskExecutorService taskExecutorService) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskExecutorService = taskExecutorService;
    }

    @PostMapping("/{taskId}/dispatch")
    @Operation(summary = "Dispatch task execution", description = "Trigger task execution through feature-flag routing.")
    public ApiResponse<InternalTaskDispatchResult> dispatchTask(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {

        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }

        TaskExecutionModeResolver.TaskExecutionDecision decision = taskExecutorService.getExecutionDecision(taskId);
        taskExecutorService.executeTask(taskId);
        return ApiResponse.success(new InternalTaskDispatchResult(
                taskId,
                "accepted",
                decision.selectedMode(),
                decision.reason()
        ));
    }

    @GetMapping("/{taskId}/execution-decision")
    @Operation(summary = "Inspect execution decision", description = "Returns current routing decision for the task.")
    public ApiResponse<TaskExecutionModeResolver.TaskExecutionDecision> getExecutionDecision(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        return ApiResponse.success(taskExecutorService.getExecutionDecision(taskId));
    }

    @PostMapping("/{taskId}/step-update")
    @Operation(summary = "Update task step status", description = "DeepAgent callback for step status/progress updates.")
    public ApiResponse<Void> updateTaskStep(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody InternalTaskStepUpdateRequest request) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        if (request == null || request.stepCode() == null || request.stepCode().isBlank()) {
            return ApiResponse.fail(400, "step_code is required");
        }

        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return ApiResponse.fail(404, "Task not found");
        }

        LocalDateTime now = LocalDateTime.now();
        List<TaskStepEntity> orderedSteps = new java.util.ArrayList<>(taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId));
        TaskStepEntity step = findOrCreateStep(taskId, request.stepCode(), orderedSteps, now);
        String nextStatus = normalizeStatus(request.status(), step.getStatus());

        step.setStatus(nextStatus);
        step.setProgress(clampProgress(request.progress()));
        if ("running".equals(nextStatus) && step.getStartedAt() == null) {
            step.setStartedAt(now);
        }
        if ("finished".equals(nextStatus) || "failed".equals(nextStatus)) {
            step.setFinishedAt(now);
        }
        step.setErrorCode(request.errorCode());
        step.setErrorMessage(request.errorMessage());
        step.setUpdatedAt(now);
        taskStepRepository.save(step);

        task.setCurrentStep(step.getStepCode());
        task.setProgress(calculateTaskProgress(orderedSteps));
        task.setUpdatedAt(now);
        if ("failed".equals(nextStatus)) {
            task.setStatus("failed");
            task.setErrorCode(request.errorCode());
            task.setErrorMessage(request.errorMessage());
        } else if ("running".equals(nextStatus)) {
            if (!"cancelled".equals(task.getStatus())) {
                task.setStatus("running");
            }
            task.setErrorCode(null);
            task.setErrorMessage(null);
        } else if ("finished".equals(nextStatus) && allStepsFinished(orderedSteps)) {
            task.setStatus("finished");
            task.setProgress(100);
            task.setErrorCode(null);
            task.setErrorMessage(null);
        }
        taskRepository.save(task);

        if (request.outputSummary() != null && !request.outputSummary().isBlank()) {
            appendTaskLog(taskId, "info", "[deepagent][" + step.getStepCode() + "] " + request.outputSummary());
        }
        if (request.errorMessage() != null && !request.errorMessage().isBlank()) {
            appendTaskLog(taskId, "error", "[deepagent][" + step.getStepCode() + "] " + request.errorMessage());
        }
        logger.info("Internal step update: taskId={}, stepCode={}, status={}, progress={}",
                taskId, step.getStepCode(), step.getStatus(), step.getProgress());
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/log")
    @Operation(summary = "Append task log", description = "DeepAgent callback for task log stream.")
    public ApiResponse<Void> appendLog(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody InternalTaskLogRequest request) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        if (request == null || request.content() == null || request.content().isBlank()) {
            return ApiResponse.fail(400, "content is required");
        }
        if (taskRepository.findById(taskId).isEmpty()) {
            return ApiResponse.fail(404, "Task not found");
        }

        String level = request.level() == null || request.level().isBlank() ? "info" : request.level();
        appendTaskLog(taskId, level, "[deepagent] " + request.content());
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/node-metrics")
    @Operation(summary = "Receive node metrics", description = "DeepAgent callback for per-node execution metrics (Phase 2 observability).")
    public ApiResponse<Void> receiveNodeMetrics(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody NodeMetricsPayload payload) {
        if (!internalToken.equals(token)) {
            return ApiResponse.fail(401, "Invalid internal token");
        }
        if (payload == null) {
            return ApiResponse.fail(400, "payload is required");
        }
        if (taskRepository.findById(taskId).isEmpty()) {
            return ApiResponse.fail(404, "Task not found");
        }

        // Store metrics as a structured task log so they're queryable via existing log APIs.
        String summary = String.format(
                "[node-metrics] node=%s run_id=%s status=%s duration_ms=%d model_calls=%d degrade=%b",
                payload.node(),
                payload.runId() != null ? payload.runId() : "",
                payload.status(),
                payload.durationMs() != null ? payload.durationMs() : 0L,
                payload.modelCalls() != null ? payload.modelCalls() : 0,
                Boolean.TRUE.equals(payload.degrade())
        );
        appendTaskLog(taskId, "info", summary);

        // Persist metrics snapshot to the corresponding task step output_summary for quick access.
        taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId).stream()
                .filter(s -> payload.node().equals(s.getStepCode()))
                .findFirst()
                .ifPresent(step -> {
                    step.setUpdatedAt(java.time.LocalDateTime.now());
                    taskStepRepository.save(step);
                });

        logger.info("node-metrics received: taskId={}, node={}, run_id={}, status={}, duration_ms={}",
                taskId, payload.node(), payload.runId(), payload.status(), payload.durationMs());
        return ApiResponse.success(null);
    }

    private TaskStepEntity findOrCreateStep(String taskId,
                                            String stepCode,
                                            List<TaskStepEntity> orderedSteps,
                                            LocalDateTime now) {
        Optional<TaskStepEntity> existing = orderedSteps.stream()
                .filter(step -> stepCode.equals(step.getStepCode()))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        int nextOrder = orderedSteps.stream()
                .map(TaskStepEntity::getStepOrder)
                .filter(order -> order != null)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0) + 1;

        TaskStepEntity step = new TaskStepEntity();
        step.setTaskId(taskId);
        step.setStepCode(stepCode);
        step.setStepName(stepCode);
        step.setStepOrder(nextOrder);
        step.setStatus("pending");
        step.setProgress(0);
        step.setRetryCount(0);
        step.setCreatedAt(now);
        step.setUpdatedAt(now);
        TaskStepEntity persisted = taskStepRepository.save(step);
        orderedSteps.add(persisted);
        return persisted;
    }

    private int calculateTaskProgress(List<TaskStepEntity> steps) {
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        int sum = 0;
        for (TaskStepEntity step : steps) {
            String status = normalizeStatus(step.getStatus(), "pending");
            int value;
            if ("finished".equals(status)) {
                value = 100;
            } else {
                value = clampProgress(step.getProgress());
            }
            sum += value;
        }
        return Math.max(0, Math.min(100, Math.round((float) sum / steps.size())));
    }

    private boolean allStepsFinished(List<TaskStepEntity> steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return steps.stream().allMatch(step -> "finished".equals(normalizeStatus(step.getStatus(), "pending")));
    }

    private int clampProgress(Integer progress) {
        if (progress == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, progress));
    }

    private String normalizeStatus(String status, String defaultStatus) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if ("running".equals(normalized) || "finished".equals(normalized) || "failed".equals(normalized) || "pending".equals(normalized)) {
            return normalized;
        }
        return defaultStatus == null || defaultStatus.isBlank() ? "pending" : defaultStatus;
    }

    private void appendTaskLog(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level == null || level.isBlank() ? "info" : level.toLowerCase(Locale.ROOT));
        log.setContent(content == null ? "" : content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }
}
