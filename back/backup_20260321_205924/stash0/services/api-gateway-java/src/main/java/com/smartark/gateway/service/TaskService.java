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
import com.smartark.gateway.db.entity.TaskRelationEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRelationRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.dto.PaperProjectSummary;
import com.smartark.gateway.dto.TaskModifyRequest;
import com.smartark.gateway.dto.TaskModifyFromPlanRequest;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.dto.TaskStatusResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.dto.TaskLogDto;
import com.smartark.gateway.dto.TaskRelationChainResult;
import java.time.ZoneId;

@Service
public class TaskService {
    private static final Logger logger = LoggerFactory.getLogger(TaskService.class);

    @Value("${smartark.preview.max-concurrent-per-user:2}")
    private int previewMaxConcurrentPerUser;

    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectRepository projectRepository;
    private final ArtifactRepository artifactRepository;
    private final TaskPreviewRepository taskPreviewRepository;
    private final TaskRelationRepository taskRelationRepository;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final TaskExecutorService taskExecutorService;
    private final PreviewDeployService previewDeployService;
    private final PreviewRuntimeService previewRuntimeService;
    private final BillingService billingService;
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository,
                       TaskStepRepository taskStepRepository,
                       TaskLogRepository taskLogRepository,
                       ProjectRepository projectRepository,
                       ArtifactRepository artifactRepository,
                       TaskPreviewRepository taskPreviewRepository,
                       TaskRelationRepository taskRelationRepository,
                       PaperTopicSessionRepository paperTopicSessionRepository,
                       PaperOutlineVersionRepository paperOutlineVersionRepository,
                       TaskExecutorService taskExecutorService,
                       PreviewDeployService previewDeployService,
                       PreviewRuntimeService previewRuntimeService,
                       BillingService billingService,
                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectRepository = projectRepository;
        this.artifactRepository = artifactRepository;
        this.taskPreviewRepository = taskPreviewRepository;
        this.taskRelationRepository = taskRelationRepository;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.taskExecutorService = taskExecutorService;
        this.previewDeployService = previewDeployService;
        this.previewRuntimeService = previewRuntimeService;
        this.billingService = billingService;
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

        return createAndStartTask(project.getId(), userId, "generate", request.instructions(), null);
    }

    public GenerateResult modify(String taskId, TaskModifyRequest request) {
        Long userId = requireUserId();
        TaskEntity parentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!parentTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        String normalized = request.changeInstructions() == null ? "" : request.changeInstructions().trim();
        logger.info("Modify requested: sourceTaskId={}, projectId={}, userId={}, changeInstructionsLength={}",
                taskId, parentTask.getProjectId(), userId, normalized.length());
        GenerateResult result = createAndStartTask(parentTask.getProjectId(), userId, "modify", request.changeInstructions(), taskId);
        appendTaskLog(taskId, "info", "Modify requested -> newTaskId=" + result.taskId());
        return result;
    }

    public TaskRelationChainResult getTaskRelations(String sourceTaskId) {
        Long userId = requireUserId();
        TaskEntity sourceTask = taskRepository.findById(sourceTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "来源任务不存在"));
        if (!sourceTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权查看此任务链路");
        }
        List<TaskRelationEntity> relationEntities = taskRelationRepository.findByParentTaskIdOrderByCreatedAtDesc(sourceTaskId);
        List<TaskRelationChainResult.ChildTaskRelation> relations = relationEntities.stream()
                .map(relation -> {
                    TaskEntity child = taskRepository.findById(relation.getChildTaskId()).orElse(null);
                    return new TaskRelationChainResult.ChildTaskRelation(
                            relation.getChildTaskId(),
                            relation.getRelationType(),
                            relation.getChatSessionId(),
                            child == null ? "unknown" : child.getStatus(),
                            child == null ? "unknown" : child.getTaskType(),
                            relation.getCreatedAt() == null ? null : relation.getCreatedAt().toString()
                    );
                })
                .sorted(Comparator.comparing(TaskRelationChainResult.ChildTaskRelation::createdAt, Comparator.nullsLast(String::compareTo)).reversed())
                .toList();
        return new TaskRelationChainResult(sourceTaskId, relations);
    }

    @Transactional
    public GenerateResult modifyFromPlan(String sourceTaskId, TaskModifyFromPlanRequest request) {
        Long userId = requireUserId();
        TaskEntity sourceTask = taskRepository.findById(sourceTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "来源任务不存在"));
        if (!sourceTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        String normalizedInstructions = request.changeInstructions() == null ? "" : request.changeInstructions().trim();
        if (normalizedInstructions.isEmpty()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "changeInstructions 不能为空");
        }
        String normalizedDoc = request.iterationDoc() == null ? "" : request.iterationDoc().trim();
        String normalizedSession = request.chatSessionId() == null ? "" : request.chatSessionId().trim();
        String payload = normalizedDoc.isEmpty()
                ? normalizedInstructions
                : normalizedInstructions + "\n\n【迭代文档】\n" + normalizedDoc;
        GenerateResult result = createAndStartTask(sourceTask.getProjectId(), userId, "modify", payload, sourceTaskId);

        TaskRelationEntity relation = new TaskRelationEntity();
        relation.setParentTaskId(sourceTaskId);
        relation.setChildTaskId(result.taskId());
        relation.setRelationType("modify_from_plan");
        relation.setChatSessionId(normalizedSession.isEmpty() ? null : normalizedSession);
        relation.setMetadataJson(toRelationMetadataJson(normalizedInstructions, normalizedDoc));
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());
        taskRelationRepository.save(relation);

        appendTaskLog(sourceTaskId, "info", "Modify from-plan requested -> newTaskId=" + result.taskId());
        appendTaskLog(result.taskId(), "info", "Task relation saved: parentTaskId=" + sourceTaskId + ", relationType=modify_from_plan");
        logger.info("Modify from-plan requested: sourceTaskId={}, childTaskId={}, userId={}, projectId={}, chatSessionId={}",
                sourceTaskId, result.taskId(), userId, sourceTask.getProjectId(), normalizedSession.isEmpty() ? "-" : normalizedSession);
        return result;
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

    private GenerateResult createAndStartTask(String projectId, Long userId, String taskType, String instructions, String sourceTaskId) {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

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
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskRepository.save(task);
        appendTaskLog(taskId, "info", "Task queued: taskType=" + taskType + ", projectId=" + projectId + ", sourceTaskId=" + (sourceTaskId == null ? "-" : sourceTaskId));
        logger.info("Task created: taskId={}, taskType={}, projectId={}, userId={}, sourceTaskId={}",
                taskId, taskType, projectId, userId, sourceTaskId == null ? "-" : sourceTaskId);

        createStep(taskId, "requirement_analyze", "需求分析", 1);
        createStep(taskId, "codegen_backend", "生成后端", 2);
        createStep(taskId, "codegen_frontend", "生成前端", 3);
        createStep(taskId, "sql_generate", "生成 SQL", 4);
        createStep(taskId, "package", "打包交付物", 5);

        appendTaskLog(taskId, "info", "Task dispatch requested: taskId=" + taskId);
        taskExecutorService.executeTask(taskId);
        logger.info("Task dispatch started: taskId={}, taskType={}", taskId, taskType);

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

        createStep(taskId, "topic_clarify", "主题澄清", 1);
        createStep(taskId, "academic_retrieve", "学术检索", 2);
        createStep(taskId, "outline_generate", "大纲生成", 3);
        createStep(taskId, "outline_expand", "内容扩展", 4);
        createStep(taskId, "outline_quality_check", "大纲质检", 5);
        createStep(taskId, "quality_rewrite", "质量回写", 6);

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

    private String toRelationMetadataJson(String changeInstructions, String iterationDoc) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "changeInstructions", changeInstructions,
                    "iterationDoc", iterationDoc
            ));
        } catch (Exception ignored) {
            return null;
        }
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

    public TaskPreviewResult getPreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                .orElseGet(() -> buildPreviewFallback(task));
        if ("ready".equals(preview.getStatus()) && isLegacyPreviewUrl(taskId, preview.getPreviewUrl())) {
            preview.setStatus("failed");
            preview.setPreviewUrl(null);
            preview.setRuntimeId(null);
            preview.setLastError("检测到旧版占位预览地址，请点击重建预览");
            preview.setUpdatedAt(LocalDateTime.now());
            if (preview.getId() != null) {
                taskPreviewRepository.save(preview);
            }
        }
        return toPreviewResult(taskId, preview);
    }

    public SseEmitter streamPreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                for (int i = 0; i < 60; i++) {
                    TaskPreviewResult preview = getPreview(taskId);
                    emitter.send(SseEmitter.event().name("message").data(Map.of(
                            "taskId", preview.taskId(),
                            "status", preview.status(),
                            "previewUrl", preview.previewUrl() == null ? "" : preview.previewUrl(),
                            "expireAt", preview.expireAt() == null ? "" : preview.expireAt(),
                            "lastError", preview.lastError() == null ? "" : preview.lastError(),
                            "phase", "provisioning".equals(preview.status()) ? "预览部署进行中" : "预览状态更新完成"
                    )));
                    if (!"provisioning".equals(preview.status())) {
                        break;
                    }
                    Thread.sleep(2000L);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
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
        if (!"finished".equals(task.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "任务未完成，暂不支持预览重建");
        }
        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId).orElse(null);
        boolean previewMissing = preview == null;
        long activeCount = taskPreviewRepository.countByUserIdAndStatusIn(userId, List.of("provisioning", "ready"));
        if (activeCount >= Math.max(previewMaxConcurrentPerUser, 1)) {
            throw new BusinessException(
                    ErrorCodes.PREVIEW_CONCURRENCY_LIMIT,
                    "预览并发数已达上限(" + Math.max(previewMaxConcurrentPerUser, 1) + ")"
            );
        }
        LocalDateTime now = LocalDateTime.now();
        if (previewMissing) {
            preview = new TaskPreviewEntity();
            preview.setTaskId(taskId);
            preview.setProjectId(task.getProjectId());
            preview.setUserId(task.getUserId());
            preview.setCreatedAt(now);
        } else if (!"failed".equals(preview.getStatus()) && !"expired".equals(preview.getStatus())) {
            throw new BusinessException(ErrorCodes.PREVIEW_REBUILD_STATE_INVALID, "仅 failed 或 expired 状态允许重建预览");
        }
        preview.setStatus("provisioning");
        preview.setPreviewUrl(null);
        preview.setRuntimeId(null);
        preview.setExpireAt(null);
        preview.setLastError(null);
        preview.setUpdatedAt(now);
        taskPreviewRepository.save(preview);
        appendTaskLog(taskId, "info", previewMissing ? "Preview record auto-created and rebuild requested" : "Preview rebuild requested");
        previewDeployService.deployPreviewAsync(taskId, true);
        return toPreviewResult(taskId, preview);
    }

    @Transactional
    public TaskPreviewResult releasePreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }
        if (!isPreviewTargetTask(task)) {
            throw new BusinessException(ErrorCodes.CONFLICT, "当前任务类型不支持预览释放");
        }
        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId).orElse(null);
        if (preview == null) {
            return toPreviewResult(taskId, buildTransientPreview(taskId, "expired", null, LocalDateTime.now(), "预览资源已释放"));
        }
        previewRuntimeService.stopRuntime(preview.getRuntimeId());
        previewRuntimeService.stopRuntimeByTaskId(taskId);
        preview.setStatus("expired");
        preview.setPreviewUrl(null);
        preview.setRuntimeId(null);
        preview.setExpireAt(LocalDateTime.now());
        preview.setLastError("预览资源已手动释放");
        preview.setUpdatedAt(LocalDateTime.now());
        taskPreviewRepository.save(preview);
        appendTaskLog(taskId, "info", "Preview released by user");
        return toPreviewResult(taskId, preview);
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

        return new PaperManuscriptResult(
                taskId,
                session.getTopic(),
                session.getTopicRefined(),
                manuscriptRoot,
                outlineVersion.getQualityScore(),
                outlineVersion.getRewriteRound()
        );
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

    private TaskPreviewEntity buildPreviewFallback(TaskEntity task) {
        if (!isPreviewTargetTask(task)) {
            return null;
        }
        if (!"finished".equals(task.getStatus())) {
            return buildTransientPreview(task.getId(), "provisioning", null, null, null);
        }
        return buildTransientPreview(task.getId(), "failed", null, null, "预览尚未初始化，请点击重建预览");
    }

    private TaskPreviewResult toPreviewResult(String taskId, TaskPreviewEntity preview) {
        if (preview == null) {
            return new TaskPreviewResult(taskId, "failed", null, null, "当前任务类型不支持预览");
        }
        return new TaskPreviewResult(
                taskId,
                preview.getStatus(),
                preview.getPreviewUrl(),
                preview.getExpireAt() == null ? null : preview.getExpireAt().toString(),
                preview.getLastError()
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

    private boolean isLegacyPreviewUrl(String taskId, String previewUrl) {
        if (previewUrl == null || previewUrl.isBlank()) {
            return false;
        }
        String normalized = previewUrl.trim();
        return normalized.equals("http://localhost:5173/preview/" + taskId)
                || normalized.endsWith("/preview/" + taskId);
    }

}
