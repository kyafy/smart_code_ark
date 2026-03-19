package com.smartark.gateway.service;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.TaskModifyRequest;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.dto.TaskStatusResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.dto.TaskLogDto;
import java.time.ZoneId;

@Service
public class TaskService {
    private final TaskRepository taskRepository;
    private final TaskStepRepository taskStepRepository;
    private final TaskLogRepository taskLogRepository;
    private final ProjectRepository projectRepository;
    private final ArtifactRepository artifactRepository;
    private final TaskExecutorService taskExecutorService;
    private final BillingService billingService;

    public TaskService(TaskRepository taskRepository,
                       TaskStepRepository taskStepRepository,
                       TaskLogRepository taskLogRepository,
                       ProjectRepository projectRepository,
                       ArtifactRepository artifactRepository,
                       TaskExecutorService taskExecutorService,
                       BillingService billingService) {
        this.taskRepository = taskRepository;
        this.taskStepRepository = taskStepRepository;
        this.taskLogRepository = taskLogRepository;
        this.projectRepository = projectRepository;
        this.artifactRepository = artifactRepository;
        this.taskExecutorService = taskExecutorService;
        this.billingService = billingService;
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

        return createAndStartTask(project.getId(), userId, "generate", null);
    }

    public GenerateResult modify(String taskId, TaskModifyRequest request) {
        Long userId = requireUserId();
        TaskEntity parentTask = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!parentTask.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        return createAndStartTask(parentTask.getProjectId(), userId, "modify", request.changeInstructions());
    }

    private GenerateResult createAndStartTask(String projectId, Long userId, String taskType, String instructions) {
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

        createStep(taskId, "requirement_analyze", "需求分析", 1);
        createStep(taskId, "codegen_backend", "生成后端", 2);
        createStep(taskId, "codegen_frontend", "生成前端", 3);
        createStep(taskId, "sql_generate", "生成 SQL", 4);
        createStep(taskId, "package", "打包交付物", 5);

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

    public TaskPreviewResult getPreview(String taskId) {
        Long userId = requireUserId();
        TaskEntity task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "任务不存在"));
        
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权操作此任务");
        }

        return new TaskPreviewResult("http://localhost:5173/preview/" + taskId);
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
}
