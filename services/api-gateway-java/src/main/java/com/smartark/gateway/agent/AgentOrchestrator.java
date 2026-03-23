package com.smartark.gateway.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.repo.ProjectSpecRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.service.PreviewDeployService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class AgentOrchestrator {
    private static final Logger logger = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectSpecRepository projectSpecRepository;
    private final PreviewDeployService previewDeployService;
    private final Map<String, AgentStep> stepMap;
    private final ObjectMapper objectMapper;

    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;
    @Value("${smartark.agent.max-retries:2}")
    private int maxRetries;
    @Value("${smartark.agent.retryable-step-codes:requirement_analyze,codegen_backend,codegen_frontend,sql_generate,rag_index_enrich,rag_retrieve_rerank}")
    private String retryableStepCodes;

    public AgentOrchestrator(TaskRepository taskRepository,
                             TaskStepRepository taskStepRepository,
                             TaskLogRepository taskLogRepository,
                             ProjectSpecRepository projectSpecRepository,
                             PreviewDeployService previewDeployService,
                             ObjectMapper objectMapper,
                             List<AgentStep> agentSteps) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.previewDeployService = previewDeployService;
        this.objectMapper = objectMapper;
        this.stepMap = agentSteps.stream().collect(Collectors.toMap(AgentStep::getStepCode, step -> step));
    }

    public void run(String taskId) {
        try {
            TaskEntity task = loadTask(taskId);
            if ("cancelled".equals(task.getStatus())) {
                log(taskId, "warn", "Task already cancelled before run");
                return;
            }
            task.setStatus("running");
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            log(taskId, "info", "Task started: " + taskId);

            ProjectSpecEntity spec = null;
            if (!"paper_outline".equals(task.getTaskType())) {
                spec = projectSpecRepository.findTopByProjectIdOrderByVersionDesc(task.getProjectId())
                        .orElseThrow(() -> new RuntimeException("Project spec not found"));
            }

            AgentExecutionContext context = new AgentExecutionContext();
            context.setTask(task);
            context.setSpec(spec);
            context.setInstructions(task.getInstructions());
            context.setNormalizedInstructions(normalizeInstructions(task.getInstructions()));
            Path workspaceDir = Paths.get(workspaceRoot, taskId);
            context.setWorkspaceDir(workspaceDir);
            context.setTaskLogger((level, content) -> log(taskId, level, content));
            if ("paper_outline".equals(task.getTaskType())) {
                fillPaperContext(context, task.getInstructions());
            }

            List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);

            for (int i = 0; i < steps.size(); i++) {
                TaskStepEntity stepEntity = steps.get(i);
                if ("finished".equals(stepEntity.getStatus())) {
                    continue;
                }

                int attempt = 0;
                while (true) {
                    attempt++;
                    task = loadTask(taskId);
                    checkCancelled(task);

                    stepEntity.setStatus("running");
                    if (stepEntity.getStartedAt() == null) {
                        stepEntity.setStartedAt(LocalDateTime.now());
                    }
                    stepEntity.setUpdatedAt(LocalDateTime.now());
                    taskStepRepository.save(stepEntity);

                    task.setCurrentStep(stepEntity.getStepCode());
                    task.setProgress((i * 100) / steps.size());
                    task.setUpdatedAt(LocalDateTime.now());
                    taskRepository.save(task);

                    log(taskId, "info", "Executing step: " + stepEntity.getStepName() + " (attempt " + attempt + ")");
                    long startedAt = System.currentTimeMillis();

                    try {
                        AgentStep agentStep = stepMap.get(stepEntity.getStepCode());
                        if (agentStep == null) {
                            throw new IllegalArgumentException("Unsupported step code: " + stepEntity.getStepCode());
                        }
                        agentStep.execute(context);
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                        log(taskId, "info", "Step completed: " + stepEntity.getStepCode() + ", elapsedMs=" + elapsedMs);
                        logStepOutputSummary(taskId, stepEntity.getStepCode(), context);
                        stepEntity.setStatus("finished");
                        stepEntity.setProgress(100);
                        stepEntity.setFinishedAt(LocalDateTime.now());
                        stepEntity.setErrorCode(null);
                        stepEntity.setErrorMessage(null);
                        stepEntity.setUpdatedAt(LocalDateTime.now());
                        taskStepRepository.save(stepEntity);
                        break;
                    } catch (Exception e) {
                        String errorCode = classifyError(e);
                        stepEntity.setStatus("failed");
                        stepEntity.setErrorCode(errorCode);
                        stepEntity.setErrorMessage(messageOf(e));
                        stepEntity.setRetryCount(stepEntity.getRetryCount() + 1);
                        stepEntity.setUpdatedAt(LocalDateTime.now());
                        taskStepRepository.save(stepEntity);
                        log(taskId, "error", "Step failed: " + stepEntity.getStepCode() + ", code=" + errorCode + ", msg=" + messageOf(e));

                        if (shouldRetry(stepEntity)) {
                            log(taskId, "warn", "Retrying step: " + stepEntity.getStepCode() + ", retryCount=" + stepEntity.getRetryCount());
                            continue;
                        }
                        throw new BusinessException(ErrorCodes.TASK_FAILED, messageOf(e));
                    }
                }
            }

            task = loadTask(taskId);
            task.setStatus("finished");
            task.setProgress(100);
            task.setCurrentStep("package");
            task.setErrorCode(null);
            task.setErrorMessage(null);
            task.setUpdatedAt(LocalDateTime.now());
            taskRepository.save(task);

            log(taskId, "info", "Task finished successfully");
            if (shouldTriggerPreviewDeploy(task)) {
                previewDeployService.deployPreviewAsync(taskId);
            }

        } catch (Exception e) {
            logger.error("Task execution failed", e);
            String errorCode = classifyError(e);
            log(taskId, "error", "Task failed: code=" + errorCode + ", message=" + messageOf(e));
            taskRepository.findById(taskId).ifPresent(t -> {
                if ("cancelled".equals(t.getStatus()) || ErrorCodes.TASK_CANCELLED == extractBusinessCode(e)) {
                    t.setStatus("cancelled");
                    t.setErrorCode(String.valueOf(ErrorCodes.TASK_CANCELLED));
                    t.setErrorMessage("任务已取消");
                } else {
                    t.setStatus("failed");
                    t.setErrorCode(errorCode);
                    t.setErrorMessage(messageOf(e));
                }
                t.setUpdatedAt(LocalDateTime.now());
                taskRepository.save(t);
            });
        }
    }

    private TaskEntity loadTask(String taskId) {
        return taskRepository.findById(taskId).orElseThrow();
    }

    private void checkCancelled(TaskEntity task) {
        if ("cancelled".equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.TASK_CANCELLED, "任务已取消");
        }
    }

    private boolean shouldRetry(TaskStepEntity stepEntity) {
        if (stepEntity.getRetryCount() > maxRetries) {
            return false;
        }
        Set<String> retryableSet = Arrays.stream(retryableStepCodes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(HashSet::new));
        return retryableSet.contains(stepEntity.getStepCode());
    }

    private String classifyError(Throwable throwable) {
        int businessCode = extractBusinessCode(throwable);
        if (businessCode == ErrorCodes.TASK_CANCELLED) {
            return String.valueOf(ErrorCodes.TASK_CANCELLED);
        }
        if (businessCode == ErrorCodes.MODEL_SERVICE_ERROR) {
            return String.valueOf(ErrorCodes.TASK_MODEL_ERROR);
        }
        if (businessCode >= ErrorCodes.RAG_EMBEDDING_FAILED && businessCode <= ErrorCodes.RAG_RETRIEVE_FAILED) {
            return String.valueOf(ErrorCodes.TASK_RAG_ERROR);
        }
        Throwable root = rootCause(throwable);
        if (root instanceof IOException) {
            return String.valueOf(ErrorCodes.TASK_IO_ERROR);
        }
        if (root instanceof IllegalArgumentException) {
            return String.valueOf(ErrorCodes.TASK_VALIDATION_ERROR);
        }
        return String.valueOf(ErrorCodes.TASK_FAILED);
    }

    private int extractBusinessCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BusinessException businessException) {
                return businessException.getCode();
            }
            current = current.getCause();
        }
        return -1;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String messageOf(Throwable throwable) {
        String msg = throwable.getMessage();
        if (msg == null || msg.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return msg;
    }

    private String normalizeInstructions(String instructions) {
        if (instructions == null) {
            return null;
        }
        String normalized = instructions.trim().replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return null;
        }
        int maxLen = 2000;
        if (normalized.length() > maxLen) {
            return normalized.substring(0, maxLen);
        }
        return normalized;
    }

    private boolean shouldTriggerPreviewDeploy(TaskEntity task) {
        return "finished".equals(task.getStatus())
                && ("generate".equals(task.getTaskType()) || "modify".equals(task.getTaskType()));
    }

    private void fillPaperContext(AgentExecutionContext context, String instructionsJson) {
        if (instructionsJson == null || instructionsJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(instructionsJson);
            context.setTopic(root.path("topic").asText(""));
            context.setDiscipline(root.path("discipline").asText(""));
            context.setDegreeLevel(root.path("degreeLevel").asText(""));
            context.setMethodPreference(root.path("methodPreference").asText(""));
        } catch (Exception e) {
            log(context.getTask().getId(), "warn", "Invalid paper instructions payload");
        }
    }

    private void logStepOutputSummary(String taskId, String stepCode, AgentExecutionContext context) {
        if ("requirement_analyze".equals(stepCode)) {
            int planSize = context.getFilePlan() == null ? 0 : context.getFilePlan().size();
            int listSize = context.getFileList() == null ? 0 : context.getFileList().size();
            log(taskId, "info", "Stage output [" + stepCode + "]: filePlan=" + planSize + ", fileList=" + listSize);
            if (context.getFilePlan() != null && !context.getFilePlan().isEmpty()) {
                String sample = context.getFilePlan().stream()
                        .filter(item -> item.getPath() != null && !item.getPath().isBlank())
                        .limit(8)
                        .map(item -> item.getPath() + "[" + item.getGroup() + "]")
                        .collect(Collectors.joining(", "));
                if (!sample.isBlank()) {
                    log(taskId, "info", "Stage output [" + stepCode + "] sample: " + sample);
                }
            }
            return;
        }
        if ("artifact_contract_validate".equals(stepCode)) {
            List<String> violations = context.getContractViolations();
            int violationCount = violations == null ? 0 : violations.size();
            log(taskId, "info", "Stage output [" + stepCode + "]: violations=" + violationCount);
            if (violationCount == 0) {
                log(taskId, "info", "Validation branch: no missing files or contract warnings, continue to package without auto-fix");
            } else {
                for (String violation : violations.stream().limit(20).toList()) {
                    log(taskId, "warn", "Validation detail: " + violation);
                }
                log(taskId, "warn", "Validation branch: warnings detected, package step will auto-fix missing critical delivery files");
            }
            return;
        }
        logWorkspaceSnapshot(taskId, stepCode, context.getWorkspaceDir());
    }

    private void logWorkspaceSnapshot(String taskId, String stepCode, Path workspaceDir) {
        if (workspaceDir == null || !Files.exists(workspaceDir)) {
            log(taskId, "warn", "Stage output [" + stepCode + "]: workspace not found");
            return;
        }
        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            log(taskId, "info", "Stage output [" + stepCode + "]: workspaceFileCount=" + files.size());
            if (!files.isEmpty()) {
                String sample = files.stream()
                        .sorted(Comparator.comparingLong(this::lastModifiedMillis).reversed())
                        .limit(8)
                        .map(path -> workspaceDir.relativize(path).toString().replace('\\', '/'))
                        .collect(Collectors.joining(", "));
                if (!sample.isBlank()) {
                    log(taskId, "info", "Stage output [" + stepCode + "] recent files: " + sample);
                }
            }
        } catch (IOException e) {
            log(taskId, "warn", "Stage output [" + stepCode + "]: failed to scan workspace, " + messageOf(e));
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void log(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }
}
