package com.smartark.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.response.PageResponse;
import com.smartark.gateway.dto.AiTaskResult;
import com.smartark.gateway.dto.AiTaskSubmitResult;
import com.smartark.gateway.dto.DemoGenerateResult;
import com.smartark.gateway.entity.AiTaskEntity;
import com.smartark.gateway.repository.AiTaskRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * AI 任务服务
 * <p>
 * 处理 AI 生成任务的生命周期管理，包括提交、查询详情、取消、重试以及列表查询。
 * 该服务负责与持久层交互，维护任务状态，并协调 {@link AiTaskWorker} 执行异步任务。
 * </p>
 */
@Service
public class AiTaskService {
    private final AiTaskRepository aiTaskRepository;
    private final AiTaskWorker aiTaskWorker;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     *
     * @param aiTaskRepository AI 任务数据仓库
     * @param aiTaskWorker     AI 任务异步执行器
     * @param objectMapper     JSON 处理工具
     */
    public AiTaskService(AiTaskRepository aiTaskRepository, AiTaskWorker aiTaskWorker, ObjectMapper objectMapper) {
        this.aiTaskRepository = aiTaskRepository;
        this.aiTaskWorker = aiTaskWorker;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交新的 AI 生成任务
     *
     * @param ownerId     任务所有者 ID
     * @param requirement 需求描述
     * @return 提交结果，包含任务 ID 和初始状态
     */
    public AiTaskSubmitResult submit(String ownerId, String requirement) {
        Instant now = Instant.now();
        AiTaskEntity entity = new AiTaskEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerId(ownerId);
        entity.setRequirement(requirement);
        entity.setStatus(TaskStatus.PENDING.name());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        AiTaskEntity saved = aiTaskRepository.save(entity);
        aiTaskWorker.execute(saved.getId());
        return new AiTaskSubmitResult(saved.getId(), saved.getStatus(), saved.getCreatedAt());
    }

    /**
     * 查询任务详情
     *
     * @param ownerId 任务所有者 ID
     * @param taskId  任务 ID
     * @return 任务详细信息
     * @throws BusinessException 如果任务不存在或不属于该用户
     */
    public AiTaskResult detail(String ownerId, String taskId) {
        AiTaskEntity entity = aiTaskRepository.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.TASK_NOT_FOUND, "任务不存在"));
        return toResult(entity);
    }

    /**
     * 取消任务
     * <p>
     * 仅当任务处于 PENDING 或 RUNNING 状态时可以取消。
     * 取消后任务状态变为 CANCELLED。
     * </p>
     *
     * @param ownerId 任务所有者 ID
     * @param taskId  任务 ID
     * @return 更新后的任务信息
     * @throws BusinessException 如果任务状态不允许取消
     */
    public AiTaskResult cancel(String ownerId, String taskId) {
        AiTaskEntity entity = aiTaskRepository.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.TASK_NOT_FOUND, "任务不存在"));
        TaskStatus currentStatus = TaskStatus.valueOf(entity.getStatus());
        if (currentStatus == TaskStatus.CANCELLED) {
            return toResult(entity);
        }
        if (currentStatus != TaskStatus.PENDING && currentStatus != TaskStatus.RUNNING) {
            throw new BusinessException(ErrorCodes.TASK_STATUS_INVALID, "当前状态不允许取消");
        }
        Instant now = Instant.now();
        entity.setStatus(TaskStatus.CANCELLED.name());
        entity.setErrorMessage("任务已取消");
        entity.setFinishedAt(now);
        entity.setUpdatedAt(now);
        return toResult(aiTaskRepository.save(entity));
    }

    /**
     * 重试任务
     * <p>
     * 仅当任务处于 FAILED 或 CANCELLED 状态时可以重试。
     * 重试会重置任务状态为 PENDING，清空结果和错误信息，并重新提交到执行队列。
     * </p>
     *
     * @param ownerId 任务所有者 ID
     * @param taskId  任务 ID
     * @return 重试提交结果
     * @throws BusinessException 如果任务状态不允许重试
     */
    public AiTaskSubmitResult retry(String ownerId, String taskId) {
        AiTaskEntity entity = aiTaskRepository.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.TASK_NOT_FOUND, "任务不存在"));
        TaskStatus currentStatus = TaskStatus.valueOf(entity.getStatus());
        if (currentStatus != TaskStatus.FAILED && currentStatus != TaskStatus.CANCELLED) {
            throw new BusinessException(ErrorCodes.TASK_STATUS_INVALID, "当前状态不允许重试");
        }
        Instant now = Instant.now();
        entity.setStatus(TaskStatus.PENDING.name());
        entity.setResultJson(null);
        entity.setErrorMessage(null);
        entity.setStartedAt(null);
        entity.setFinishedAt(null);
        entity.setUpdatedAt(now);
        AiTaskEntity saved = aiTaskRepository.save(entity);
        aiTaskWorker.execute(saved.getId());
        return new AiTaskSubmitResult(saved.getId(), saved.getStatus(), saved.getUpdatedAt());
    }

    /**
     * 分页查询用户的任务列表
     *
     * @param ownerId 任务所有者 ID
     * @param page    页码（从1开始）
     * @param size    每页数量
     * @return 任务列表的分页结果
     */
    public PageResponse<AiTaskResult> list(String ownerId, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<AiTaskEntity> pageData = aiTaskRepository.findByOwnerId(
                ownerId,
                PageRequest.of(safePage - 1, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        List<AiTaskResult> items = pageData.getContent().stream().map(this::toResult).toList();
        return PageResponse.of(items, pageData.getTotalElements(), safePage, safeSize);
    }

    private AiTaskResult toResult(AiTaskEntity entity) {
        return new AiTaskResult(
                entity.getId(),
                entity.getRequirement(),
                entity.getStatus(),
                parseResult(entity.getResultJson()),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getUpdatedAt()
        );
    }

    private DemoGenerateResult parseResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, DemoGenerateResult.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("任务结果反序列化失败", ex);
        }
    }
}
