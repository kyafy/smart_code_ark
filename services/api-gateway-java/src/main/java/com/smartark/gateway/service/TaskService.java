package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.db.entity.PaperCorpusChunkEntity;
import com.smartark.gateway.db.entity.PaperCorpusDocEntity;
import com.smartark.gateway.db.repo.PaperCorpusChunkRepository;
import com.smartark.gateway.db.repo.PaperCorpusDocRepository;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.dto.ContractReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.dto.GenerateOptions;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.dto.PaperProjectSummary;
import com.smartark.gateway.dto.PaperTraceabilityResult;
import com.smartark.gateway.dto.RagRetrievalResult;
import com.smartark.gateway.dto.TaskModifyRequest;
import com.smartark.gateway.dto.PreviewLogsResult;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.dto.TaskStatusResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.dto.TaskLogDto;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;

@Service
public class TaskService {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");
    private static final String RULE_MISSING_REQUIRED_FILE = "missing_required_file";
    private static final String RULE_INVALID_COMPOSE_CONTEXT = "invalid_compose_context";
    private static final String RULE_INVALID_START_SCRIPT = "invalid_start_script";

    @Value("${smartark.preview.max-concurrent-per-user:2}")
    private int previewMaxConcurrentPerUser;
    @Value("${smartark.preview.log-dir:/tmp/smartark/preview-logs}")
    private String previewLogDir;
    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;

    @Autowired(required = false)
    private ContainerRuntimeService containerRuntimeService;
    @Autowired(required = false)
    private TemplateRepoService templateRepoService;

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectRepository projectRepository;
    private final ArtifactRepository artifactRepository;
    private final TaskPreviewRepository taskPreviewRepository;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final PaperCorpusDocRepository paperCorpusDocRepository;
    private final PaperCorpusChunkRepository paperCorpusChunkRepository;
    private final TaskExecutorService taskExecutorService;
    private final PreviewDeployService previewDeployService;
    private final BillingService billingService;
    private final StepMemoryService stepMemoryService;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository,
                       TaskStepRepository taskStepRepository,
                       TaskLogRepository taskLogRepository,
                       ProjectRepository projectRepository,
                       ArtifactRepository artifactRepository,
                       TaskPreviewRepository taskPreviewRepository,
                       PaperTopicSessionRepository paperTopicSessionRepository,
                       PaperOutlineVersionRepository paperOutlineVersionRepository,
                       PaperCorpusDocRepository paperCorpusDocRepository,
                       PaperCorpusChunkRepository paperCorpusChunkRepository,
                       TaskExecutorService taskExecutorService,
                       PreviewDeployService previewDeployService,
                       BillingService billingService,
                       StepMemoryService stepMemoryService,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectRepository = projectRepository;
        this.artifactRepository = artifactRepository;
        this.taskPreviewRepository = taskPreviewRepository;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.paperCorpusDocRepository = paperCorpusDocRepository;
        this.paperCorpusChunkRepository = paperCorpusChunkRepository;
        this.taskExecutorService = taskExecutorService;
        this.previewDeployService = previewDeployService;
        this.billingService = billingService;
        this.stepMemoryService = stepMemoryService;
        this.objectMapper = objectMapper;
    }

    private Long requireUserId() {
        String userIdStr = RequestContext.getUserId();
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未授权访问");
        }
        return Long.parseLong(userIdStr);
    }

    public GenerateResult generate(GenerateRequest request) {
        Long userId = requireUserId();
        ProjectEntity project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "项目不存在"));
        
        if (!project.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此项目");
        }

        return createAndStartTask(project.getId(), userId, "generate", request.instructions(), request.options());
    }

    public GenerateResult modify(String taskId, TaskModifyRequest request) {
        Long userId = requireUserId();
        TaskEntity parentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!parentTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        GenerateOptions options = new GenerateOptions(
                parentTask.getDeliveryLevelRequested(),
                parentTask.getTemplateId(),
                Boolean.FALSE,
                Boolean.TRUE,
                Boolean.TRUE
        );
        return createAndStartTask(parentTask.getProjectId(), userId, "modify", request.changeInstructions(), options);
    }

    public PaperOutlineGenerateResult generatePaperOutline(PaperOutlineGenerateRequest request) {
        Long userId = requireUserId();
        String projectId = "paper_outline_" + userId;
        String taskPayload;
        try {
            taskPayload = objectMapper.writeValueAsString(Map.of(
                    "topic", request.topic(),
                    "discipline", request.discipline(),
                    "degreeLevel", request.degreeLevel(),
                    "methodPreference", request.methodPreference() == null ? "" : request.methodPreference()
            ));
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "论文主题参数序列化失败");
        }
        GenerateResult result = createAndStartPaperTask(projectId, userId, taskPayload);
        return new PaperOutlineGenerateResult(result.taskId(), result.status());
    }

    @Transactional
    public GenerateResult cancelTask(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!"queued".equals(task.getStatus()) && !"running".equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "当前状态不允许取消任务");
        }
        task.setStatus("cancelled");
        task.setErrorCode(String.valueOf(ErrorCodes.TASK_CANCELLED));
        task.setErrorMessage("用户取消任务");
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        appendTaskLog(taskId, "warn", "Task cancelled by user");
        return new GenerateResult(taskId, "cancelled");
    }

    @Transactional
    public GenerateResult retryStep(String taskId, String stepCode) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!"failed".equals(task.getStatus()) && !"cancelled".equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "当前状态不允许重试");
        }

        List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);
        TaskStepEntity target = steps.stream()
                .filter(s -> stepCode.equals(s.getStepCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "步骤不存在"));

        for (TaskStepEntity step : steps) {
            if (step.getStepOrder() >= target.getStepOrder()) {
                step.setStatus("pending");
                step.setProgress(0);
                step.setStartedAt(null);
                step.setFinishedAt(null);
                step.setErrorCode(null);
                step.setErrorMessage(null);
                step.setUpdatedAt(LocalDateTime.now());
                taskStepRepository.save(step);
                stepMemoryService.clearStep(taskId, step.getStepCode());
            }
        }

        task.setStatus("running");
        task.setCurrentStep(stepCode);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);

        appendTaskLog(taskId, "info", "Manual retry from step: " + stepCode);
        taskExecutorService.executeTask(taskId);
        return new GenerateResult(taskId, "running");
    }

    private GenerateResult createAndStartTask(String projectId, Long userId, String taskType, String instructions, GenerateOptions options) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();
        GenerateOptions normalizedOptions = options == null ? GenerateOptions.defaultOptions() : options;
        if (normalizedOptions.requiresTemplate()
                && (normalizedOptions.templateId() == null || normalizedOptions.templateId().isBlank())) {
            throw new BusinessException(
                    ErrorCodes.TEMPLATE_REQUIRED_FOR_DELIVERABLE,
                    "templateId is required for validated or deliverable tasks"
            );
        }
        if (normalizedOptions.templateId() != null
                && !normalizedOptions.templateId().isBlank()
                && templateRepoService != null
                && !templateRepoService.templateExists(normalizedOptions.templateId())) {
            throw new BusinessException(
                    ErrorCodes.TEMPLATE_NOT_FOUND,
                    "template not found: " + normalizedOptions.templateId()
            );
        }

        // 计费校验与扣除：每个生成/修改任务消耗 10 额度
        billingService.deductQuota(projectId, taskId, 10, taskType);

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setInstructions(instructions);
        task.setStatus("queued");
        task.setProgress(0);
        task.setDeliveryLevelRequested(normalizedOptions.deliveryLevel());
        task.setDeliveryLevelActual("draft");
        task.setDeliveryStatus("pending");
        task.setTemplateId(normalizedOptions.templateId());
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        createStep(taskId, "requirement_analyze", "需求分析", 1);
        createStep(taskId, "codegen_backend", "生成后端", 2);
        createStep(taskId, "codegen_frontend", "生成前端", 3);
        createStep(taskId, "sql_generate", "生成 SQL", 4);
        createStep(taskId, "artifact_contract_validate", "交付物校验", 5);
        createStep(taskId, "build_verify", "构建验证", 6);
        createStep(taskId, "runtime_smoke_test", "Runtime smoke test", 7);
        createStep(taskId, "package", "打包交付物", 8);

        taskExecutorService.executeTask(taskId);

        return new GenerateResult(taskId, "queued");
    }

    private GenerateResult createAndStartPaperTask(String projectId, Long userId, String instructions) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setUserId(userId);
        task.setTaskType("paper_outline");
        task.setInstructions(instructions);
        task.setStatus("queued");
        task.setProgress(0);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);

        createStep(taskId, "topic_clarify",         "主题澄清",       1);
        createStep(taskId, "academic_retrieve",     "学术检索",       2);
        createStep(taskId, "rag_index_enrich",      "RAG索引构建",    3);
        createStep(taskId, "rag_retrieve_rerank",   "RAG检索重排",    4);
        createStep(taskId, "outline_generate",      "大纲生成",       5);
        createStep(taskId, "outline_expand",        "内容扩展",       6);
        createStep(taskId, "outline_quality_check", "大纲质检",       7);
        createStep(taskId, "quality_rewrite",       "质量回写",       8);

        taskExecutorService.executeTask(taskId);

        return new GenerateResult(taskId, "queued");
    }

    private void createStep(String taskId, String code, String name, int order) {
        TaskStepEntity step = new TaskStepEntity();
        step.setTaskId(taskId);
        step.setStepCode(code);
        step.setStepName(name);
        step.setStepOrder(order);
        step.setStatus("pending");
        step.setProgress(0);
        step.setRetryCount(0);
        step.setCreatedAt(LocalDateTime.now());
        step.setUpdatedAt(LocalDateTime.now());
        taskStepRepository.save(step);
    }

    public TaskStatusResult getStatus(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        // Find startedAt and finishedAt from steps
        List<TaskStepEntity> steps = taskStepRepository.findByTaskIdOrderByStepOrderAsc(taskId);
        String startedAt = null;
        String finishedAt = null;
        if (!steps.isEmpty()) {
            LocalDateTime firstStart = steps.get(0).getStartedAt();
            if (firstStart != null) startedAt = firstStart.toString();
            LocalDateTime lastFinish = steps.get(steps.size() - 1).getFinishedAt();
            if (lastFinish != null) finishedAt = lastFinish.toString();
        }

        return new TaskStatusResult(
                task.getStatus(),
                task.getProgress(),
                task.getCurrentStep(),
                task.getCurrentStep(),
                task.getProjectId(),
                task.getErrorCode(),
                task.getErrorMessage(),
                startedAt,
                finishedAt
        );
    }

    public ContractReportResult getContractReport(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "task not found"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "forbidden");
        }

        Path reportPath = resolveTaskWorkspacePath(taskId).resolve("contract_report.json");
        if (!Files.exists(reportPath)) {
            throw new BusinessException(ErrorCodes.DELIVERY_REPORT_NOT_FOUND, "contract_report.json not found");
        }
        try {
            String json = Files.readString(reportPath, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, ContractReportResult.class);
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "failed to parse contract_report.json");
        }
    }

    public DeliveryReportResult getDeliveryReport(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "task not found"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "forbidden");
        }

        Path reportPath = resolveTaskWorkspacePath(taskId).resolve("delivery_report.json");
        if (!Files.exists(reportPath)) {
            throw new BusinessException(ErrorCodes.DELIVERY_REPORT_NOT_FOUND, "delivery_report.json not found");
        }
        try {
            String json = Files.readString(reportPath, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, DeliveryReportResult.class);
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "failed to parse delivery_report.json");
        }
    }

    public ContractReportResult validateDelivery(String taskId, Boolean autoFix) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "task not found"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "forbidden");
        }
        if (!"finished".equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.DELIVERY_VALIDATE_STATE_INVALID, "task status must be finished for delivery validation");
        }

        boolean autoFixEnabled = Boolean.TRUE.equals(autoFix);
        appendTaskLog(taskId, "info", "Delivery validate start, autoFix=" + autoFixEnabled);
        ContractReportResult result = validateDeliveryWorkspace(taskId, autoFixEnabled);
        appendTaskLog(taskId, "info", "Delivery validate result, passed=" + result.passed());
        return result;
    }

    public TaskPreviewResult getPreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                .orElseGet(() -> buildPreviewFallback(task));
        return toPreviewResult(taskId, preview);
    }

    @Transactional
    public TaskPreviewResult rebuildPreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!isPreviewTargetTask(task)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "当前任务类型不支持预览重建");
        }
        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "预览记录不存在"));
        if (!"failed".equals(preview.getStatus()) && !"expired".equals(preview.getStatus())) {
            throw new BusinessException(ErrorCodes.PREVIEW_REBUILD_STATE_INVALID, "仅 failed 或 expired 状态允许重建预览");
        }
        long activeCount = taskPreviewRepository.countByUserIdAndStatusIn(userId, List.of("provisioning", "ready"));
        if (activeCount >= Math.max(previewMaxConcurrentPerUser, 1)) {
            throw new BusinessException(
                    ErrorCodes.PREVIEW_CONCURRENCY_LIMIT,
                    "预览并发数已达上限(" + Math.max(previewMaxConcurrentPerUser, 1) + ")"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        preview.setStatus("provisioning");
        preview.setPhase(null);
        preview.setPreviewUrl(null);
        preview.setExpireAt(null);
        preview.setLastError(null);
        preview.setLastErrorCode(null);
        preview.setUpdatedAt(now);
        taskPreviewRepository.save(preview);
        appendTaskLog(taskId, "info", "Preview rebuild requested");
        previewDeployService.deployPreviewAsync(taskId);
        return toPreviewResult(taskId, preview);
    }

    public PreviewLogsResult getPreviewLogs(String taskId, int tail) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        List<PreviewLogsResult.LogLine> logLines = new ArrayList<>();

        // Try to get container logs first
        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId).orElse(null);
        if (preview != null && containerRuntimeService != null
                && preview.getRuntimeId() != null
                && containerRuntimeService.isContainerRunning(preview.getRuntimeId())) {
            String containerLogs = containerRuntimeService.getContainerLogs(preview.getRuntimeId(), tail);
            if (containerLogs != null && !containerLogs.isBlank()) {
                long now = System.currentTimeMillis();
                for (String line : containerLogs.split("\n")) {
                    logLines.add(new PreviewLogsResult.LogLine(now, "info", line));
                }
            }
        }

        // Also include build logs from file if available
        if (logLines.isEmpty() && preview != null && preview.getBuildLogUrl() != null) {
            String logUrl = preview.getBuildLogUrl();
            if (logUrl.startsWith("file://")) {
                try {
                    java.nio.file.Path logPath = java.nio.file.Paths.get(logUrl.substring(7));
                    if (java.nio.file.Files.exists(logPath)) {
                        List<String> lines = java.nio.file.Files.readAllLines(logPath);
                        int start = Math.max(0, lines.size() - tail);
                        long ts = System.currentTimeMillis();
                        for (int i = start; i < lines.size(); i++) {
                            logLines.add(new PreviewLogsResult.LogLine(ts, "info", lines.get(i)));
                        }
                    }
                } catch (Exception e) {
                    logLines.add(new PreviewLogsResult.LogLine(System.currentTimeMillis(), "error",
                            "Failed to read log file: " + e.getMessage()));
                }
            }
        }

        // Fallback: return task logs related to preview
        if (logLines.isEmpty()) {
            List<TaskLogEntity> taskLogs = taskLogRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
            for (TaskLogEntity log : taskLogs) {
                if (log.getContent() != null && (log.getContent().contains("Preview") || log.getContent().contains("preview"))) {
                    logLines.add(new PreviewLogsResult.LogLine(
                            log.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                            log.getLevel(),
                            log.getContent()
                    ));
                }
            }
        }

        // Apply tail limit
        if (logLines.size() > tail) {
            logLines = logLines.subList(logLines.size() - tail, logLines.size());
        }

        return new PreviewLogsResult(taskId, logLines);
    }

    public PaperOutlineResult getPaperOutline(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!"paper_outline".equals(task.getTaskType())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务类型不匹配");
        }

        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文会话不存在"));
        PaperOutlineVersionEntity outlineVersion = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文大纲尚未生成"));

        JsonNode outlineRoot = parseJson(outlineVersion.getOutlineJson());
        JsonNode manuscriptRoot = parseJson(outlineVersion.getManuscriptJson());
        JsonNode qualityRoot = parseJson(outlineVersion.getQualityReportJson());

        JsonNode chapters = outlineRoot.path("chapters");
        JsonNode references = outlineRoot.path("references");
        List<String> researchQuestions = readStringArray(outlineRoot.path("researchQuestions"));
        if (researchQuestions.isEmpty()) {
            researchQuestions = readStringArray(parseJson(session.getResearchQuestionsJson()));
        }

        return new PaperOutlineResult(
                taskId,
                outlineVersion.getCitationStyle(),
                session.getTopic(),
                session.getTopicRefined(),
                researchQuestions,
                chapters.isMissingNode() ? objectMapper.createArrayNode() : chapters,
                manuscriptRoot.isMissingNode() ? objectMapper.createObjectNode() : manuscriptRoot,
                qualityRoot.isMissingNode() ? objectMapper.createObjectNode() : qualityRoot,
                references.isMissingNode() ? objectMapper.createArrayNode() : references,
                outlineVersion.getQualityScore(),
                outlineVersion.getRewriteRound()
        );
    }

    public List<PaperProjectSummary> listPaperProjects() {
        Long userId = requireUserId();
        List<TaskEntity> tasks = taskRepository.findByUserIdAndTaskTypeOrderByUpdatedAtDesc(userId, "paper_outline");
        List<PaperProjectSummary> result = new ArrayList<>();
        for (TaskEntity task : tasks) {
            PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(task.getId()).orElse(null);
            if (session == null) {
                continue;
            }
            result.add(new PaperProjectSummary(
                    task.getId(),
                    session.getTopic(),
                    session.getDiscipline(),
                    session.getDegreeLevel(),
                    task.getStatus(),
                    task.getUpdatedAt() == null ? null : task.getUpdatedAt().toString()
            ));
        }
        return result;
    }

    public PaperManuscriptResult getPaperManuscript(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!"paper_outline".equals(task.getTaskType())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务类型不匹配");
        }

        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文会话不存在"));
        PaperOutlineVersionEntity outlineVersion = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文文稿尚未生成"));

        JsonNode manuscriptRoot = parseJson(outlineVersion.getManuscriptJson());
        if (manuscriptRoot.isMissingNode() || manuscriptRoot.isNull() || manuscriptRoot.isEmpty()) {
            manuscriptRoot = parseJson(outlineVersion.getOutlineJson());
        }
        manuscriptRoot = patchManuscriptForDisplay(manuscriptRoot);

        return new PaperManuscriptResult(
                taskId,
                session.getTopic(),
                session.getTopicRefined(),
                manuscriptRoot,
                outlineVersion.getQualityScore(),
                outlineVersion.getRewriteRound()
        );
    }
    
    public RagRetrievalResult getRagRetrieval(String taskId) {
        return getRagRetrieval(taskId, false);
    }

    public RagRetrievalResult getRagRetrieval(String taskId, boolean rerankedOnly) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文会话不存在"));

        List<PaperCorpusDocEntity> docs = paperCorpusDocRepository.findBySessionId(session.getId());
        List<RagEvidenceItem> evidenceItems = new ArrayList<>();
        long totalChunks = 0;

        for (PaperCorpusDocEntity doc : docs) {
            List<PaperCorpusChunkEntity> chunks = paperCorpusChunkRepository.findByDocId(doc.getId());
            totalChunks += chunks.size();
            for (PaperCorpusChunkEntity chunk : chunks) {
                RagEvidenceItem item = new RagEvidenceItem();
                item.setChunkUid(chunk.getChunkUid());
                item.setDocUid(doc.getDocUid());
                item.setPaperId(doc.getPaperId());
                item.setTitle(doc.getTitle());
                item.setContent(chunk.getContent());
                item.setUrl(doc.getUrl());
                item.setYear(doc.getYear());
                item.setChunkType(chunk.getChunkType());
                item.setVectorScore(0);
                item.setRerankScore(0);
                if (!rerankedOnly || item.getRerankScore() > 0) {
                    evidenceItems.add(item);
                }
            }
        }

        return new RagRetrievalResult(taskId, evidenceItems, totalChunks);
    }

    public PaperTraceabilityResult getPaperTraceability(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限操作");
        }
        if (!"paper_outline".equals(task.getTaskType())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务类型不匹配");
        }

        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopByTaskIdOrderByVersionNoDesc(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "论文版本不存在"));

        List<PaperTraceabilityResult.EvidenceItem> evidenceItems = parseEvidenceList(version.getChapterEvidenceMapJson());
        List<PaperTraceabilityResult.ChapterEvidence> chapters = extractChapterCitations(version.getManuscriptJson());
        boolean hasChapterCitations = chapters.stream().anyMatch(c -> c.citationIndices() != null && !c.citationIndices().isEmpty());
        if (evidenceItems.isEmpty() || !hasChapterCitations) {
            if (evidenceItems.isEmpty()) {
                evidenceItems = extractEvidenceListFromOutline(version.getOutlineJson());
            }
            if (!hasChapterCitations) {
                chapters = extractChapterCitationsFromOutline(version.getOutlineJson(), evidenceItems);
            }
        }
        int totalChunks = computeTotalChunks(taskId);
        return new PaperTraceabilityResult(taskId, chapters, evidenceItems, totalChunks);
    }

    public byte[] getDownload(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        
        List<ArtifactEntity> artifacts = artifactRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        ArtifactEntity zipArtifact = artifacts.stream()
                .filter(a -> "zip".equals(a.getArtifactType()))
                .reduce((first, second) -> second) // Get the latest one
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "生成产物不存在，请等待任务完成"));

        String url = zipArtifact.getStorageUrl();
        if (url.startsWith("file://")) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(url.substring(7));
                return java.nio.file.Files.readAllBytes(path);
            } catch (java.io.IOException e) {
                throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "读取产物文件失败");
            }
        }
        
        throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "不支持的存储协议: " + url);
    }

    public List<TaskLogDto> getLogs(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));

        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        List<TaskLogEntity> logs = taskLogRepository.findByTaskIdOrderByCreatedAtAsc(taskId);
        return logs.stream().map(log -> new TaskLogDto(
                log.getId(),
                log.getLevel(),
                log.getContent(),
                log.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )).toList();
    }

    private void appendTaskLog(String taskId, String level, String content) {
        TaskLogEntity log = new TaskLogEntity();
        log.setTaskId(taskId);
        log.setLevel(level);
        log.setContent(content);
        log.setCreatedAt(LocalDateTime.now());
        taskLogRepository.save(log);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        node.forEach(n -> {
            String value = n.asText("");
            if (!value.isBlank()) {
                result.add(value);
            }
        });
        return result;
    }

    private List<PaperTraceabilityResult.EvidenceItem> parseEvidenceList(String chapterEvidenceMapJson) {
        if (chapterEvidenceMapJson == null || chapterEvidenceMapJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(chapterEvidenceMapJson);
            List<PaperTraceabilityResult.EvidenceItem> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode item : root) {
                    result.add(new PaperTraceabilityResult.EvidenceItem(
                            item.path("citationIndex").asInt(0),
                            item.path("chunkUid").asText(null),
                            item.path("docUid").asText(null),
                            item.path("paperId").asText(null),
                            item.path("title").asText(null),
                            item.path("content").asText(null),
                            item.path("url").asText(null),
                            item.path("year").isMissingNode() || item.path("year").isNull() ? null : item.path("year").asInt(),
                            item.path("source").asText(null),
                            item.path("vectorScore").isNumber() ? item.path("vectorScore").asDouble() : null,
                            item.path("rerankScore").isNumber() ? item.path("rerankScore").asDouble() : null,
                            item.path("chunkType").asText(null)
                    ));
                }
            }
            return result.stream()
                    .filter(e -> e.citationIndex() > 0)
                    .sorted(Comparator.comparingInt(PaperTraceabilityResult.EvidenceItem::citationIndex))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<PaperTraceabilityResult.ChapterEvidence> extractChapterCitations(String manuscriptJson) {
        JsonNode manuscript = parseJson(manuscriptJson);
        JsonNode chaptersNode = manuscript.path("chapters");
        if (!chaptersNode.isArray()) {
            return List.of();
        }
        List<PaperTraceabilityResult.ChapterEvidence> chapters = new ArrayList<>();
        int idx = 0;
        for (JsonNode chapter : chaptersNode) {
            List<Integer> citationIndices = new ArrayList<>();
            JsonNode sections = chapter.path("sections");
            if (sections.isArray()) {
                for (JsonNode section : sections) {
                    JsonNode citationsNode = section.path("citations");
                    if (citationsNode.isArray()) {
                        citationsNode.forEach(citation -> {
                            int citationValue = citation.asInt(-1);
                            if (citationValue > 0 && !citationIndices.contains(citationValue)) {
                                citationIndices.add(citationValue);
                            }
                        });
                    }
                    String content = section.path("content").asText("");
                    Matcher matcher = CITATION_PATTERN.matcher(content);
                    while (matcher.find()) {
                        int citationValue = Integer.parseInt(matcher.group(1));
                        if (!citationIndices.contains(citationValue)) {
                            citationIndices.add(citationValue);
                        }
                    }
                }
            }
            chapters.add(new PaperTraceabilityResult.ChapterEvidence(
                    chapter.path("title").asText(""),
                    idx,
                    citationIndices.stream().sorted().toList()
            ));
            idx++;
        }
        return chapters;
    }

    private int computeTotalChunks(String taskId) {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(taskId).orElse(null);
        if (session == null) {
            return 0;
        }
        List<PaperCorpusDocEntity> docs = paperCorpusDocRepository.findBySessionId(session.getId());
        int total = 0;
        for (PaperCorpusDocEntity doc : docs) {
            total += paperCorpusChunkRepository.findByDocId(doc.getId()).size();
        }
        return total;
    }

    private JsonNode patchManuscriptForDisplay(JsonNode manuscriptRoot) {
        if (!manuscriptRoot.isObject()) {
            return manuscriptRoot;
        }
        JsonNode chaptersNode = manuscriptRoot.path("chapters");
        if (!chaptersNode.isArray()) {
            return manuscriptRoot;
        }
        for (JsonNode chapter : chaptersNode) {
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray()) {
                continue;
            }
            String chapterSummary = chapter.path("summary").asText("");
            for (JsonNode section : sections) {
                if (!(section instanceof ObjectNode sectionObject)) {
                    continue;
                }
                String content = section.path("content").asText("");
                if (content == null || content.isBlank()) {
                    String fallbackContent = section.path("coreArgument").asText("");
                    if (fallbackContent == null || fallbackContent.isBlank()) {
                        fallbackContent = chapterSummary;
                    }
                    if (fallbackContent == null || fallbackContent.isBlank()) {
                        fallbackContent = "该节暂无扩写内容";
                    }
                    sectionObject.put("content", fallbackContent);
                }
            }
        }
        return manuscriptRoot;
    }

    private List<PaperTraceabilityResult.EvidenceItem> extractEvidenceListFromOutline(String outlineJson) {
        JsonNode outline = parseJson(outlineJson);
        JsonNode chaptersNode = outline.path("chapters");
        if (!chaptersNode.isArray()) {
            return List.of();
        }
        Map<String, Integer> citationIndexMap = new LinkedHashMap<>();
        List<PaperTraceabilityResult.EvidenceItem> evidenceItems = new ArrayList<>();
        for (JsonNode chapter : chaptersNode) {
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray()) {
                continue;
            }
            for (JsonNode section : sections) {
                JsonNode subsections = section.path("subsections");
                if (!subsections.isArray()) {
                    continue;
                }
                for (JsonNode subsection : subsections) {
                    JsonNode evidences = subsection.path("evidence");
                    if (!evidences.isArray()) {
                        continue;
                    }
                    for (JsonNode evidence : evidences) {
                        String key = buildOutlineEvidenceKey(evidence);
                        if (key.isBlank()) {
                            continue;
                        }
                        Integer citationIndex = citationIndexMap.get(key);
                        if (citationIndex == null) {
                            citationIndex = citationIndexMap.size() + 1;
                            citationIndexMap.put(key, citationIndex);
                            Integer year = null;
                            JsonNode yearNode = evidence.path("year");
                            if (yearNode.isInt() || yearNode.isLong()) {
                                year = yearNode.asInt();
                            } else {
                                String yearText = yearNode.asText("");
                                if (!yearText.isBlank()) {
                                    try {
                                        year = Integer.parseInt(yearText);
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                            String paperId = evidence.path("paperId").asText(null);
                            evidenceItems.add(new PaperTraceabilityResult.EvidenceItem(
                                    citationIndex,
                                    null,
                                    null,
                                    paperId,
                                    evidence.path("title").asText(null),
                                    null,
                                    evidence.path("url").asText(null),
                                    year,
                                    extractSource(paperId),
                                    null,
                                    null,
                                    null
                            ));
                        }
                    }
                }
            }
        }
        return evidenceItems;
    }

    private List<PaperTraceabilityResult.ChapterEvidence> extractChapterCitationsFromOutline(
            String outlineJson,
            List<PaperTraceabilityResult.EvidenceItem> evidenceItems
    ) {
        JsonNode outline = parseJson(outlineJson);
        JsonNode chaptersNode = outline.path("chapters");
        if (!chaptersNode.isArray()) {
            return List.of();
        }
        Map<String, Integer> citationIndexMap = new LinkedHashMap<>();
        for (PaperTraceabilityResult.EvidenceItem evidenceItem : evidenceItems) {
            if (evidenceItem == null) {
                continue;
            }
            String key = buildOutlineEvidenceKey(evidenceItem.paperId(), evidenceItem.url(), evidenceItem.title());
            if (!key.isBlank()) {
                citationIndexMap.putIfAbsent(key, evidenceItem.citationIndex());
            }
        }

        List<PaperTraceabilityResult.ChapterEvidence> chapters = new ArrayList<>();
        int chapterIdx = 0;
        for (JsonNode chapter : chaptersNode) {
            List<Integer> chapterCitationIndices = new ArrayList<>();
            JsonNode sections = chapter.path("sections");
            if (sections.isArray()) {
                for (JsonNode section : sections) {
                    JsonNode subsections = section.path("subsections");
                    if (!subsections.isArray()) {
                        continue;
                    }
                    for (JsonNode subsection : subsections) {
                        JsonNode evidences = subsection.path("evidence");
                        if (!evidences.isArray()) {
                            continue;
                        }
                        for (JsonNode evidence : evidences) {
                            Integer citationIndex = citationIndexMap.get(buildOutlineEvidenceKey(evidence));
                            if (citationIndex != null && citationIndex > 0 && !chapterCitationIndices.contains(citationIndex)) {
                                chapterCitationIndices.add(citationIndex);
                            }
                        }
                    }
                }
            }
            chapters.add(new PaperTraceabilityResult.ChapterEvidence(
                    chapter.path("chapter").asText(chapter.path("title").asText("")),
                    chapterIdx,
                    chapterCitationIndices.stream().sorted().toList()
            ));
            chapterIdx++;
        }
        return chapters;
    }

    private String buildOutlineEvidenceKey(JsonNode evidence) {
        return buildOutlineEvidenceKey(
                evidence.path("paperId").asText(""),
                evidence.path("url").asText(""),
                evidence.path("title").asText("")
        );
    }

    private String buildOutlineEvidenceKey(String paperId, String url, String title) {
        if (paperId != null && !paperId.isBlank()) {
            return "paperId:" + paperId;
        }
        if (url != null && !url.isBlank()) {
            return "url:" + url;
        }
        if (title != null && !title.isBlank()) {
            return "title:" + title;
        }
        return "";
    }

    private String extractSource(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return "unknown";
        }
        int idx = paperId.indexOf(':');
        if (idx <= 0) {
            return "unknown";
        }
        return paperId.substring(0, idx);
    }

    private ContractReportResult validateDeliveryWorkspace(String taskId, boolean autoFixEnabled) {
        Path workspacePath = resolveTaskWorkspacePath(taskId);
        String backendDir = detectBackendDirForDelivery(workspacePath);
        String frontendDir = detectFrontendDirForDelivery(workspacePath);
        List<String> fixedActions = new ArrayList<>();

        if (autoFixEnabled) {
            tryAutoFixDelivery(workspacePath, backendDir, frontendDir, fixedActions);
        }

        List<String> failedRules = collectDeliveryFailedRules(workspacePath, frontendDir);
        ContractReportResult report = new ContractReportResult(
                failedRules.isEmpty(),
                failedRules,
                fixedActions,
                LocalDateTime.now().toString()
        );
        try {
            writeContractReportFile(workspacePath.resolve("contract_report.json"), report);
            return report;
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "failed to write contract_report.json");
        }
    }

    private List<String> collectDeliveryFailedRules(Path workspacePath, String frontendDir) {
        List<String> failedRules = new ArrayList<>();
        if (hasMissingRequiredFileForDelivery(workspacePath, frontendDir)) {
            addUnique(failedRules, RULE_MISSING_REQUIRED_FILE);
        }
        if (hasInvalidComposeContextForDelivery(workspacePath)) {
            addUnique(failedRules, RULE_INVALID_COMPOSE_CONTEXT);
        }
        if (hasInvalidStartScriptForDelivery(workspacePath)) {
            addUnique(failedRules, RULE_INVALID_START_SCRIPT);
        }
        return failedRules;
    }

    private void tryAutoFixDelivery(Path workspacePath, String backendDir, String frontendDir, List<String> fixedActions) {
        try {
            Files.createDirectories(workspacePath);
            ensureStartScriptForDelivery(workspacePath, fixedActions);
            ensureFrontendPackageJsonForDelivery(workspacePath, frontendDir, fixedActions);
            ensureComposeForDelivery(workspacePath, backendDir, frontendDir, fixedActions);
        } catch (IOException e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "auto-fix delivery artifacts failed");
        }
    }

    private void ensureStartScriptForDelivery(Path workspacePath, List<String> fixedActions) throws IOException {
        Path scriptPath = workspacePath.resolve("scripts/start.sh");
        String content =
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose up --build -d\n" +
                        "echo \"services started\"\n";
        if (!Files.exists(scriptPath)) {
            if (scriptPath.getParent() != null) {
                Files.createDirectories(scriptPath.getParent());
            }
            Files.writeString(scriptPath, content, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_scripts_start_sh");
            return;
        }
        String existing = Files.readString(scriptPath, StandardCharsets.UTF_8);
        if (!existing.contains("docker compose up --build -d") && !existing.contains("docker compose up -d")) {
            Files.writeString(scriptPath, content, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_scripts_start_sh");
        }
    }

    private void ensureFrontendPackageJsonForDelivery(Path workspacePath, String frontendDir, List<String> fixedActions) throws IOException {
        Path frontendPath = workspacePath.resolve(frontendDir);
        Path packageJsonPath = frontendPath.resolve("package.json");
        if (Files.exists(packageJsonPath)) {
            return;
        }
        Files.createDirectories(frontendPath);
        String appName = frontendDir.replace("/", "-");
        String packageJson =
                "{\n" +
                        "  \"name\": \"" + appName + "\",\n" +
                        "  \"private\": true,\n" +
                        "  \"version\": \"0.0.1\",\n" +
                        "  \"scripts\": {\n" +
                        "    \"dev\": \"vite\",\n" +
                        "    \"build\": \"vite build\",\n" +
                        "    \"preview\": \"vite preview\"\n" +
                        "  },\n" +
                        "  \"dependencies\": {\n" +
                        "    \"vue\": \"^3.5.13\"\n" +
                        "  },\n" +
                        "  \"devDependencies\": {\n" +
                        "    \"vite\": \"^5.4.10\",\n" +
                        "    \"@vitejs/plugin-vue\": \"^5.1.4\"\n" +
                        "  }\n" +
                        "}\n";
        Files.writeString(packageJsonPath, packageJson, StandardCharsets.UTF_8);
        addUnique(fixedActions, "generated_frontend_package_json");
    }

    private void ensureComposeForDelivery(Path workspacePath, String backendDir, String frontendDir, List<String> fixedActions) throws IOException {
        Path composePath = workspacePath.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            String content =
                    "services:\n" +
                            "  backend:\n" +
                            "    build:\n" +
                            "      context: ./" + backendDir + "\n" +
                            "    ports:\n" +
                            "      - \"8080:8080\"\n" +
                            "  frontend:\n" +
                            "    build:\n" +
                            "      context: ./" + frontendDir + "\n" +
                            "    ports:\n" +
                            "      - \"5173:5173\"\n";
            Files.writeString(composePath, content, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docker_compose_yml");
            return;
        }

        List<String> lines = Files.readAllLines(composePath, StandardCharsets.UTF_8);
        List<String> fixed = new ArrayList<>();
        String activeService = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (line.startsWith("  ") && !line.startsWith("    ") && trimmed.endsWith(":")) {
                activeService = trimmed.substring(0, trimmed.length() - 1).toLowerCase(Locale.ROOT);
            }

            if (!trimmed.startsWith("context:")) {
                fixed.add(line);
                continue;
            }
            String raw = trimmed.substring("context:".length()).trim().replace("\"", "").replace("'", "");
            String normalized = raw.startsWith("./") ? raw.substring(2) : raw;
            Path candidate = workspacePath.resolve(normalized).normalize();
            if (!raw.isBlank() && Files.isDirectory(candidate)) {
                fixed.add(line);
                continue;
            }

            String replacement = chooseComposeContextForDelivery(activeService, backendDir, frontendDir);
            int contextIndex = line.indexOf("context:");
            String indent = contextIndex < 0 ? "" : line.substring(0, contextIndex);
            fixed.add(indent + "context: ./" + replacement);
            addUnique(fixedActions, "repaired_compose_context:" + normalizeComposeServiceForDelivery(activeService, replacement, backendDir, frontendDir));
        }
        Files.write(composePath, fixed, StandardCharsets.UTF_8);
    }

    private boolean hasMissingRequiredFileForDelivery(Path workspacePath, String frontendDir) {
        if (!Files.exists(workspacePath.resolve("docker-compose.yml"))) {
            return true;
        }
        if (!Files.exists(workspacePath.resolve("scripts/start.sh"))) {
            return true;
        }
        return !Files.exists(workspacePath.resolve(frontendDir).resolve("package.json"));
    }

    private boolean hasInvalidComposeContextForDelivery(Path workspacePath) {
        Path composePath = workspacePath.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            return true;
        }
        try {
            List<String> lines = Files.readAllLines(composePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("context:")) {
                    continue;
                }
                String raw = trimmed.substring("context:".length()).trim().replace("\"", "").replace("'", "");
                if (raw.isBlank()) {
                    return true;
                }
                String normalized = raw.startsWith("./") ? raw.substring(2) : raw;
                if (!Files.isDirectory(workspacePath.resolve(normalized).normalize())) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean hasInvalidStartScriptForDelivery(Path workspacePath) {
        Path startScript = workspacePath.resolve("scripts/start.sh");
        if (!Files.exists(startScript)) {
            return true;
        }
        try {
            String content = Files.readString(startScript, StandardCharsets.UTF_8);
            return !content.contains("docker compose up --build -d") && !content.contains("docker compose up -d");
        } catch (IOException e) {
            return true;
        }
    }

    private String detectBackendDirForDelivery(Path workspacePath) {
        List<String> candidates = List.of("backend", "services/api-gateway-java", "api", "server");
        for (String candidate : candidates) {
            Path candidatePath = workspacePath.resolve(candidate);
            if (Files.exists(candidatePath.resolve("pom.xml"))
                    || Files.exists(candidatePath.resolve("build.gradle"))
                    || Files.exists(candidatePath.resolve("package.json"))) {
                return candidate.replace("\\", "/").replaceAll("^\\./+", "");
            }
        }
        return "backend";
    }

    private String detectFrontendDirForDelivery(Path workspacePath) {
        List<String> candidates = List.of("frontend", "frontend-web", "web", "client", "app");
        for (String candidate : candidates) {
            Path candidatePath = workspacePath.resolve(candidate);
            if (Files.exists(candidatePath.resolve("package.json"))
                    || Files.exists(candidatePath.resolve("vite.config.ts"))
                    || Files.exists(candidatePath.resolve("src"))) {
                return candidate.replace("\\", "/").replaceAll("^\\./+", "");
            }
        }
        return "frontend";
    }

    private String chooseComposeContextForDelivery(String serviceName, String backendDir, String frontendDir) {
        String service = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
        if (service.contains("front") || service.contains("web") || service.contains("client")) {
            return frontendDir;
        }
        if (service.contains("back") || service.contains("api") || service.contains("server")) {
            return backendDir;
        }
        return backendDir;
    }

    private String normalizeComposeServiceForDelivery(String serviceName, String replacement, String backendDir, String frontendDir) {
        String service = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
        if (service.contains("front") || service.contains("web") || service.contains("client")) {
            return "frontend";
        }
        if (service.contains("back") || service.contains("api") || service.contains("server")) {
            return "backend";
        }
        if (frontendDir.equals(replacement)) {
            return "frontend";
        }
        if (backendDir.equals(replacement)) {
            return "backend";
        }
        return service.isBlank() ? "backend" : service;
    }

    private void writeContractReportFile(Path reportPath, ContractReportResult report) throws IOException {
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(reportPath, content, StandardCharsets.UTF_8);
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank() || values.contains(value)) {
            return;
        }
        values.add(value);
    }

    private TaskPreviewEntity buildPreviewFallback(TaskEntity task) {
        if (!isPreviewTargetTask(task)) {
            return null;
        }
        if (!"finished".equals(task.getStatus())) {
            return buildTransientPreview(task.getId(), "provisioning", null, null, null);
        }
        return buildTransientPreview(task.getId(), "failed", null, null, "预览未就绪，请重试重建预览");
    }

    private TaskPreviewResult toPreviewResult(String taskId, TaskPreviewEntity preview) {
        if (preview == null) {
            return new TaskPreviewResult(taskId, "failed", null, null, null, "当前任务类型不支持预览", null, null);
        }
        return new TaskPreviewResult(
                taskId,
                preview.getStatus(),
                preview.getPhase(),
                preview.getPreviewUrl(),
                preview.getExpireAt() == null ? null : preview.getExpireAt().toString(),
                preview.getLastError(),
                preview.getLastErrorCode(),
                preview.getBuildLogUrl()
        );
    }

    private TaskPreviewEntity buildTransientPreview(String taskId, String status, String previewUrl, LocalDateTime expireAt, String lastError) {
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setTaskId(taskId);
        preview.setStatus(status);
        preview.setPreviewUrl(previewUrl);
        preview.setExpireAt(expireAt);
        preview.setLastError(lastError);
        return preview;
    }

    private boolean isPreviewTargetTask(TaskEntity task) {
        return "generate".equals(task.getTaskType()) || "modify".equals(task.getTaskType());
    }

    private Path resolveTaskWorkspacePath(String taskId) {
        return Paths.get(workspaceRoot, taskId).toAbsolutePath();
    }

}
