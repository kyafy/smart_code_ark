package com.smartark.gateway.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.model.FilePlanItem;
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
import com.smartark.gateway.service.ContextAssembler;
import com.smartark.gateway.dto.LangchainHealthResult;
import com.smartark.gateway.dto.LangchainMemoryReadRequest;
import com.smartark.gateway.dto.LangchainMemoryReadResult;
import com.smartark.gateway.dto.LangchainMemoryWriteRequest;
import com.smartark.gateway.service.LangchainSidecarClient;
import com.smartark.gateway.service.LongTermMemoryService;
import com.smartark.gateway.service.PreviewDeployService;
import com.smartark.gateway.service.QualityGateService;
import com.smartark.gateway.service.StepMemoryService;
import com.smartark.gateway.service.TaskMemoryService;
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
import java.util.HashMap;
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
    private final LangchainSidecarClient langchainSidecarClient;
    private final TaskMemoryService taskMemoryService;
    private final LongTermMemoryService longTermMemoryService;
    private final ContextAssembler contextAssembler;
    private final QualityGateService qualityGateService;
    private final StepMemoryService stepMemoryService;
    private final Map<String, AgentStep> stepMap;
    private final ObjectMapper objectMapper;

    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;
    @Value("${smartark.agent.max-retries:2}")
    private int maxRetries;
    @Value("${smartark.agent.retryable-step-codes:requirement_analyze,codegen_backend,codegen_frontend,sql_generate,rag_index_enrich,rag_retrieve_rerank}")
    private String retryableStepCodes;
    @Value("${smartark.langchain.enabled:false}")
    private boolean langchainEnabled;
    @Value("${smartark.memory.short-term.top-k:8}")
    private int shortTermTopK;
    @Value("${smartark.memory.long-term.top-k:8}")
    private int longTermTopK;
    @Value("${smartark.quality-gate.enabled:false}")
    private boolean qualityGateEnabled;
    @Value("${smartark.quality-gate.min-score:0.66}")
    private double qualityGateMinScore;
    @Value("${smartark.quality-gate.auto-fix-enabled:true}")
    private boolean qualityGateAutoFixEnabled;
    @Value("${smartark.quality-gate.max-retries:2}")
    private int qualityGateMaxRetries;

    public AgentOrchestrator(TaskRepository taskRepository,
                             TaskStepRepository taskStepRepository,
                             TaskLogRepository taskLogRepository,
                             ProjectSpecRepository projectSpecRepository,
                             PreviewDeployService previewDeployService,
                             LangchainSidecarClient langchainSidecarClient,
                             TaskMemoryService taskMemoryService,
                             LongTermMemoryService longTermMemoryService,
                             ContextAssembler contextAssembler,
                             QualityGateService qualityGateService,
                             StepMemoryService stepMemoryService,
                             ObjectMapper objectMapper,
                             List<AgentStep> agentSteps) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectSpecRepository = projectSpecRepository;
        this.previewDeployService = previewDeployService;
        this.langchainSidecarClient = langchainSidecarClient;
        this.taskMemoryService = taskMemoryService;
        this.longTermMemoryService = longTermMemoryService;
        this.contextAssembler = contextAssembler;
        this.qualityGateService = qualityGateService;
        this.stepMemoryService = stepMemoryService;
        this.objectMapper = objectMapper;
        this.stepMap = agentSteps.stream().collect(Collectors.toMap(AgentStep::getStepCode, step -> step));
    }

    public void run(String taskId) {
        long taskStartedAt = System.currentTimeMillis();
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
            context.setSidecarCallCount(0);
            context.setTaskLogger((level, content) -> log(taskId, level, content));
            if ("paper_outline".equals(task.getTaskType())) {
                fillPaperContext(context, task.getInstructions());
            }
            probeSidecarHealth(taskId, context);

            List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);

            for (int i = 0; i < steps.size(); i++) {
                TaskStepEntity stepEntity = steps.get(i);
                if ("finished".equals(stepEntity.getStatus())) {
                    hydrateContextFromStepMemory(taskId, stepEntity.getStepCode(), context);
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
                    loadAndAttachShortTermMemory(taskId, stepEntity.getStepCode(), context);
                    loadAndAttachLongTermMemory(task, stepEntity.getStepCode(), context);
                    assembleContextPack(taskId, stepEntity.getStepCode(), context);

                    log(taskId, "info", "Executing step: " + stepEntity.getStepName() + " (attempt " + attempt + ")");
                    long startedAt = System.currentTimeMillis();
                    int sidecarCallsBeforeStep = context.getSidecarCallCount();
                    log(taskId, "info", "step_probe event=start taskId=" + taskId + ", stepCode=" + stepEntity.getStepCode()
                            + ", sidecarCallCount=" + sidecarCallsBeforeStep);

                    try {
                        AgentStep agentStep = stepMap.get(stepEntity.getStepCode());
                        if (agentStep == null) {
                            throw new IllegalArgumentException("Unsupported step code: " + stepEntity.getStepCode());
                        }
                        agentStep.execute(context);
                        runQualityGateIfNeeded(taskId, stepEntity.getStepCode(), context);
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                        int stepSidecarCalls = Math.max(0, context.getSidecarCallCount() - sidecarCallsBeforeStep);
                        log(taskId, "info", "Step completed: " + stepEntity.getStepCode() + ", elapsedMs=" + elapsedMs);
                        log(taskId, "info", "step_probe event=finish taskId=" + taskId + ", stepCode=" + stepEntity.getStepCode()
                                + ", durationMs=" + elapsedMs + ", sidecarCallCount=" + stepSidecarCalls);
                        logStepOutputSummary(taskId, stepEntity.getStepCode(), context);
                        writeShortTermMemory(taskId, stepEntity.getStepCode(), i + 1, context, null);
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
                        long elapsedMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                        int stepSidecarCalls = Math.max(0, context.getSidecarCallCount() - sidecarCallsBeforeStep);
                        stepEntity.setStatus("failed");
                        stepEntity.setErrorCode(errorCode);
                        stepEntity.setErrorMessage(messageOf(e));
                        stepEntity.setRetryCount(stepEntity.getRetryCount() + 1);
                        stepEntity.setUpdatedAt(LocalDateTime.now());
                        taskStepRepository.save(stepEntity);
                        log(taskId, "error", "Step failed: " + stepEntity.getStepCode() + ", code=" + errorCode + ", msg=" + messageOf(e));
                        log(taskId, "error", "step_probe event=failed taskId=" + taskId + ", stepCode=" + stepEntity.getStepCode()
                                + ", durationMs=" + elapsedMs + ", sidecarCallCount=" + stepSidecarCalls
                                + ", errorCode=" + errorCode);
                        writeShortTermMemory(taskId, stepEntity.getStepCode(), i + 1, context, messageOf(e));

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

            long totalDurationMs = Math.max(0L, System.currentTimeMillis() - taskStartedAt);
            log(taskId, "info", "Task finished successfully");
            log(taskId, "info", "task_probe event=finish taskId=" + taskId + ", durationMs=" + totalDurationMs
                    + ", sidecarCallCount=" + context.getSidecarCallCount());
            if (shouldTriggerPreviewDeploy(task)) {
                previewDeployService.deployPreviewAsync(taskId);
            }

        } catch (Exception e) {
            logger.error("Task execution failed", e);
            String errorCode = classifyError(e);
            long totalDurationMs = Math.max(0L, System.currentTimeMillis() - taskStartedAt);
            log(taskId, "error", "Task failed: code=" + errorCode + ", message=" + messageOf(e));
            log(taskId, "error", "task_probe event=failed taskId=" + taskId + ", durationMs=" + totalDurationMs
                    + ", sidecarCallCount=-1, errorCode=" + errorCode);
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

    private void probeSidecarHealth(String taskId, AgentExecutionContext context) {
        if (!langchainEnabled) {
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainHealthResult result = langchainSidecarClient.health();
            context.incrementSidecarCallCount();
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log(taskId, "info", "sidecar_probe endpoint=/health status=" + result.status()
                    + ", durationMs=" + durationMs + ", sidecarCallCount=" + context.getSidecarCallCount());
        } catch (Exception e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log(taskId, "warn", "sidecar_probe endpoint=/health status=failed, durationMs=" + durationMs
                    + ", error=" + messageOf(e));
        }
    }

    private void runQualityGateIfNeeded(String taskId, String stepCode, AgentExecutionContext context) {
        if (!qualityGateEnabled) {
            return;
        }
        if (!"artifact_contract_validate".equals(stepCode)) {
            return;
        }
        int configuredRetries = Math.max(0, qualityGateMaxRetries);
        int maxAttempts = qualityGateAutoFixEnabled
                ? Math.max(2, configuredRetries + 1)
                : Math.max(1, configuredRetries + 1);
        boolean autoFixApplied = false;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            long startedAt = System.currentTimeMillis();
            QualityGateService.QualityGateResult result = qualityGateService.evaluate(context.getWorkspaceDir());
            qualityGateService.persistReport(context.getWorkspaceDir(), result);
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            log(taskId, "info", "quality_gate event=finish taskId=" + taskId + ", stepCode=" + stepCode
                    + ", attempt=" + attempt + "/" + maxAttempts
                    + ", passed=" + result.passed() + ", score=" + result.score()
                    + ", failedRules=" + result.failedRules() + ", durationMs=" + durationMs
                    + ", autoFixApplied=" + autoFixApplied);
            if (result.passed() && result.score() >= qualityGateMinScore) {
                return;
            }

            if (!autoFixApplied && qualityGateAutoFixEnabled) {
                List<String> fixedActions = qualityGateService.autoFix(context.getWorkspaceDir(), result.failedRules());
                autoFixApplied = true;
                log(taskId, "info", "quality_gate event=autofix taskId=" + taskId + ", stepCode=" + stepCode
                        + ", fixedActions=" + fixedActions);
            }

            if (attempt < maxAttempts) {
                log(taskId, "warn", "quality_gate event=retry taskId=" + taskId + ", stepCode=" + stepCode
                        + ", nextAttempt=" + (attempt + 1));
            } else {
                throw new BusinessException(
                        ErrorCodes.TASK_VALIDATION_ERROR,
                        "quality gate failed, score=" + result.score() + ", failedRules=" + result.failedRules()
                );
            }
        }
    }

    private void loadAndAttachShortTermMemory(String taskId, String stepCode, AgentExecutionContext context) {
        List<TaskMemoryService.MemoryEntry> recent = taskMemoryService.readRecent(taskId, shortTermTopK);
        List<String> localMemoryLines = recent.stream()
                .map(entry -> "[" + entry.sequence() + "] " + entry.stepCode()
                        + " prompt=" + truncateForLog(entry.promptSummary(), 120)
                        + " output=" + truncateForLog(entry.outputSummary(), 120)
                        + (entry.failureReason() == null || entry.failureReason().isBlank() ? "" : ", failure=" + truncateForLog(entry.failureReason(), 120)))
                .toList();
        List<String> sidecarMemoryLines = loadShortTermMemoryFromSidecar(taskId, stepCode, context);
        List<String> memoryLines = new java.util.ArrayList<>(localMemoryLines);
        memoryLines.addAll(sidecarMemoryLines);
        context.setShortTermMemories(memoryLines);
        log(taskId, "info", "memory_probe event=load taskId=" + taskId + ", stepCode=" + stepCode
                + ", entries=" + memoryLines.size() + ", localEntries=" + localMemoryLines.size()
                + ", sidecarEntries=" + sidecarMemoryLines.size());
    }

    private void loadAndAttachLongTermMemory(TaskEntity task, String stepCode, AgentExecutionContext context) {
        String stackSignature = resolveStackSignature(context);
        List<LongTermMemoryService.MemoryItem> items = longTermMemoryService.readTopK(
                task.getProjectId(),
                task.getUserId(),
                stackSignature,
                longTermTopK
        );
        List<String> localMemoryLines = items.stream()
                .map(item -> "[" + item.memoryType() + "] " + item.stepCode() + ": " + truncateForLog(item.summary(), 150))
                .toList();
        List<String> sidecarMemoryLines = loadLongTermMemoryFromSidecar(task, stepCode, stackSignature, context);
        List<String> memoryLines = new java.util.ArrayList<>(localMemoryLines);
        memoryLines.addAll(sidecarMemoryLines);
        context.setLongTermMemories(memoryLines);
        log(task.getId(), "info", "memory_probe event=load_long_term taskId=" + task.getId()
                + ", stepCode=" + stepCode + ", entries=" + memoryLines.size()
                + ", localEntries=" + localMemoryLines.size()
                + ", sidecarEntries=" + sidecarMemoryLines.size()
                + ", stackSignature=" + stackSignature);
    }

    private void assembleContextPack(String taskId, String stepCode, AgentExecutionContext context) {
        String prd = extractPrd(context);
        String baseInstructions = normalizeInstructions(context.getInstructions());
        ContextAssembler.AssembledContext assembled = contextAssembler.assemble(
                stepCode,
                prd,
                baseInstructions,
                context.getShortTermMemories(),
                context.getLongTermMemories()
        );
        context.setAssembledContextPack(assembled.contextPack());
        log(taskId, "info", "context_probe taskId=" + taskId + ", stepCode=" + stepCode
                + ", sources=" + assembled.sources()
                + ", shortTermCount=" + assembled.shortTermCount()
                + ", longTermCount=" + assembled.longTermCount()
                + ", truncated=" + assembled.truncated()
                + ", contextLength=" + assembled.contextPack().length());
    }

    private void writeShortTermMemory(String taskId, String stepCode, int sequence, AgentExecutionContext context, String failureReason) {
        String promptSummary = summarizePrompt(context);
        String outputSummary = summarizeStepOutput(stepCode, context);
        List<String> fixedActions = "package".equals(stepCode)
                ? extractPackageFixedActions(context)
                : List.of();
        taskMemoryService.append(taskId, stepCode, sequence, promptSummary, outputSummary, failureReason, fixedActions);
        TaskEntity task = context.getTask();
        if (task != null) {
            String stackSignature = resolveStackSignature(context);
            String memoryType = failureReason == null || failureReason.isBlank() ? "success_pattern" : "failure_pattern";
            String summary = memoryType + " | step=" + stepCode + " | output=" + outputSummary
                    + (failureReason == null || failureReason.isBlank() ? "" : " | failure=" + failureReason);
            longTermMemoryService.append(
                    task.getProjectId(),
                    task.getUserId(),
                    stackSignature,
                    taskId,
                    stepCode,
                    memoryType,
                    summary,
                    fixedActions
            );
            writeMemoryToSidecar(task, stackSignature, stepCode, memoryType, summary, fixedActions, context);
        }
        log(taskId, "info", "memory_probe event=write taskId=" + taskId + ", stepCode=" + stepCode
                + ", sequence=" + sequence + ", hasFailure=" + (failureReason != null && !failureReason.isBlank()));
    }

    private List<String> loadShortTermMemoryFromSidecar(String taskId, String stepCode, AgentExecutionContext context) {
        if (!langchainEnabled) {
            return List.of();
        }
        try {
            LangchainMemoryReadResult result = langchainSidecarClient.readMemory(
                    new LangchainMemoryReadRequest("task", taskId, stepCode, shortTermTopK)
            );
            context.incrementSidecarCallCount();
            if (result == null || result.items() == null || result.items().isEmpty()) {
                return List.of();
            }
            List<String> lines = result.items().stream()
                    .map(item -> "[sidecar:" + item.memoryType() + "] " + truncateForLog(item.content(), 160))
                    .toList();
            return lines;
        } catch (Exception e) {
            logger.warn("Load short-term memory from sidecar failed taskId={}, stepCode={}, error={}",
                    taskId, stepCode, messageOf(e));
            return List.of();
        }
    }

    private List<String> loadLongTermMemoryFromSidecar(TaskEntity task, String stepCode, String stackSignature, AgentExecutionContext context) {
        if (!langchainEnabled) {
            return List.of();
        }
        String scopeId = task.getProjectId() + ":" + task.getUserId() + ":" + stackSignature;
        try {
            LangchainMemoryReadResult result = langchainSidecarClient.readMemory(
                    new LangchainMemoryReadRequest("project_user_stack", scopeId, stepCode, longTermTopK)
            );
            context.incrementSidecarCallCount();
            if (result == null || result.items() == null || result.items().isEmpty()) {
                return List.of();
            }
            return result.items().stream()
                    .map(item -> "[sidecar:" + item.memoryType() + "] " + truncateForLog(item.content(), 180))
                    .toList();
        } catch (Exception e) {
            logger.warn("Load long-term memory from sidecar failed taskId={}, stepCode={}, error={}",
                    task.getId(), stepCode, messageOf(e));
            return List.of();
        }
    }

    private void writeMemoryToSidecar(TaskEntity task,
                                      String stackSignature,
                                      String stepCode,
                                      String memoryType,
                                      String summary,
                                      List<String> fixedActions,
                                      AgentExecutionContext context) {
        if (!langchainEnabled) {
            return;
        }
        try {
            HashMap<String, Object> metadata = new HashMap<>();
            metadata.put("taskId", task.getId());
            metadata.put("projectId", task.getProjectId());
            metadata.put("userId", task.getUserId());
            metadata.put("stepCode", stepCode);
            metadata.put("stackSignature", stackSignature);
            metadata.put("fixedActions", fixedActions == null ? List.of() : fixedActions);

            langchainSidecarClient.writeMemory(new LangchainMemoryWriteRequest(
                    "project_user_stack",
                    task.getProjectId() + ":" + task.getUserId() + ":" + stackSignature,
                    memoryType,
                    summary,
                    metadata
            ));
            context.incrementSidecarCallCount();
        } catch (Exception e) {
            logger.warn("Write memory to sidecar failed taskId={}, stepCode={}, error={}",
                    task.getId(), stepCode, messageOf(e));
        }
    }

    private String summarizePrompt(AgentExecutionContext context) {
        String normalized = context.getNormalizedInstructions();
        if (normalized == null || normalized.isBlank()) {
            normalized = context.getInstructions();
        }
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        return truncateForLog(normalized, 200);
    }

    private String summarizeStepOutput(String stepCode, AgentExecutionContext context) {
        if ("requirement_analyze".equals(stepCode)) {
            int planSize = context.getFilePlan() == null ? 0 : context.getFilePlan().size();
            int listSize = context.getFileList() == null ? 0 : context.getFileList().size();
            return "filePlan=" + planSize + ",fileList=" + listSize;
        }
        if ("artifact_contract_validate".equals(stepCode)) {
            int violations = context.getContractViolations() == null ? 0 : context.getContractViolations().size();
            return "violations=" + violations;
        }
        Path workspaceDir = context.getWorkspaceDir();
        if (workspaceDir == null || !Files.exists(workspaceDir)) {
            return "workspace=missing";
        }
        try (Stream<Path> stream = Files.walk(workspaceDir)) {
            long fileCount = stream.filter(Files::isRegularFile).count();
            return "workspaceFileCount=" + fileCount;
        } catch (IOException e) {
            return "workspaceScanFailed";
        }
    }

    private List<String> extractPackageFixedActions(AgentExecutionContext context) {
        Path reportPath = context.getWorkspaceDir() == null ? null : context.getWorkspaceDir().resolve("contract_report.json");
        if (reportPath == null || !Files.exists(reportPath)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(reportPath));
            JsonNode fixedNode = root.path("fixedActions");
            if (!fixedNode.isArray()) {
                return List.of();
            }
            List<String> actions = new java.util.ArrayList<>();
            fixedNode.forEach(node -> {
                String value = node.asText("");
                if (!value.isBlank()) {
                    actions.add(value);
                }
            });
            return actions;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String truncateForLog(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String extractPrd(AgentExecutionContext context) {
        if (context.getSpec() == null || context.getSpec().getRequirementJson() == null) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(context.getSpec().getRequirementJson());
            return root.path("prd").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveStackSignature(AgentExecutionContext context) {
        if (context.getSpec() == null || context.getSpec().getRequirementJson() == null) {
            return "springboot+vue3+mysql";
        }
        try {
            JsonNode root = objectMapper.readTree(context.getSpec().getRequirementJson());
            String backend = root.at("/stack/backend").asText("springboot");
            String frontend = root.at("/stack/frontend").asText("vue3");
            String db = root.at("/stack/db").asText("mysql");
            return backend + "+" + frontend + "+" + db;
        } catch (Exception e) {
            return "springboot+vue3+mysql";
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

    private void hydrateContextFromStepMemory(String taskId, String stepCode, AgentExecutionContext context) {
        try {
            if ("requirement_analyze".equals(stepCode)) {
                if (context.getFilePlan() == null || context.getFilePlan().isEmpty()) {
                    stepMemoryService.loadRaw(taskId, stepCode, "filePlan").ifPresent(json -> {
                        try {
                            List<FilePlanItem> plan = objectMapper.readValue(json, new TypeReference<List<FilePlanItem>>() {});
                            context.setFilePlan(plan);
                            log(taskId, "info", "Hydrated filePlan from step memory: size=" + plan.size());
                        } catch (Exception e) {
                            logger.warn("Failed to deserialize filePlan from step memory", e);
                        }
                    });
                }
                if (context.getFileList() == null || context.getFileList().isEmpty()) {
                    stepMemoryService.loadRaw(taskId, stepCode, "fileList").ifPresent(json -> {
                        try {
                            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                            context.setFileList(list);
                        } catch (Exception e) {
                            logger.warn("Failed to deserialize fileList from step memory", e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            logger.warn("hydrateContextFromStepMemory failed for taskId={}, stepCode={}", taskId, stepCode, e);
        }
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
