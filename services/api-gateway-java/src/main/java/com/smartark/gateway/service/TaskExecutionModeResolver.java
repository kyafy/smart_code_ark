package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves whether a task should run on legacy orchestrator or deepagent pipeline.
 */
@Service
public class TaskExecutionModeResolver {
    private final TaskRepository taskRepository;

    @Value("${smartark.agent.executor-mode:legacy}")
    private String executorMode;

    @Value("${smartark.agent.executor-ab-ratio:0}")
    private int executorAbRatio;

    @Value("${smartark.agent.deepagent.supported-task-types:generate,modify}")
    private String deepAgentSupportedTaskTypes;

    public TaskExecutionModeResolver(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    public TaskExecutionDecision resolveByTaskId(String taskId) {
        TaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return new TaskExecutionDecision(taskId, "", normalizedExecutorMode(), "legacy",
                    "task_not_found", 0, clampAbRatio(executorAbRatio), false);
        }
        return resolve(taskId, task.getTaskType());
    }

    public TaskExecutionDecision resolve(String taskId, String taskType) {
        String mode = normalizedExecutorMode();
        int abRatio = clampAbRatio(executorAbRatio);
        int bucket = Math.floorMod((taskId == null ? "" : taskId).hashCode(), 100);
        String normalizedTaskType = normalize(taskType);
        boolean eligible = supportedTaskTypes().contains(normalizedTaskType);

        if ("deepagent".equals(mode)) {
            if (eligible) {
                return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "deepagent",
                        "forced_deepagent", bucket, abRatio, true);
            }
            return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "legacy",
                    "task_type_not_supported", bucket, abRatio, false);
        }

        if ("ab".equals(mode)) {
            if (!eligible) {
                return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "legacy",
                        "task_type_not_supported", bucket, abRatio, false);
            }
            if (bucket < abRatio) {
                return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "deepagent",
                        "ab_hit", bucket, abRatio, true);
            }
            return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "legacy",
                    "ab_miss", bucket, abRatio, true);
        }

        return new TaskExecutionDecision(taskId, normalizedTaskType, mode, "legacy",
                "forced_legacy", bucket, abRatio, eligible);
    }

    private String normalizedExecutorMode() {
        String normalized = normalize(executorMode);
        if ("deepagent".equals(normalized) || "ab".equals(normalized)) {
            return normalized;
        }
        return "legacy";
    }

    private Set<String> supportedTaskTypes() {
        return Arrays.stream((deepAgentSupportedTaskTypes == null ? "" : deepAgentSupportedTaskTypes).split(","))
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    private int clampAbRatio(int ratio) {
        return Math.max(0, Math.min(100, ratio));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record TaskExecutionDecision(
            String taskId,
            String taskType,
            String configuredMode,
            String selectedMode,
            String reason,
            int abBucket,
            int abRatio,
            boolean deepAgentEligible
    ) {
        public boolean isDeepAgentSelected() {
            return "deepagent".equals(selectedMode);
        }
    }
}
