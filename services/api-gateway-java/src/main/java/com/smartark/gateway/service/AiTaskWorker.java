package com.smartark.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.client.AIServiceClient;
import com.smartark.gateway.dto.DemoGenerateResult;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.RequirementResult;
import com.smartark.gateway.entity.AiTaskEntity;
import com.smartark.gateway.repository.AiTaskRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * AI 任务异步工作器
 * <p>
 * 负责在后台线程池中执行具体的 AI 生成任务。
 * 包含需求解析、代码生成、结果回写以及错误处理逻辑。
 * </p>
 */
@Service
public class AiTaskWorker {
    private final AiTaskRepository aiTaskRepository;
    private final AIServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     *
     * @param aiTaskRepository AI 任务数据仓库
     * @param aiServiceClient  AI 服务客户端
     * @param objectMapper     JSON 处理工具
     */
    public AiTaskWorker(AiTaskRepository aiTaskRepository, AIServiceClient aiServiceClient, ObjectMapper objectMapper) {
        this.aiTaskRepository = aiTaskRepository;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 AI 任务
     * <p>
     * 该方法标记为 {@link Async}，将在独立的线程池中执行。
     * 执行流程：
     * 1. 检查任务状态，仅处理 PENDING 状态的任务
     * 2. 更新状态为 RUNNING
     * 3. 调用 AI 服务进行需求解析和代码生成
     * 4. 检查任务是否被取消
     * 5. 保存结果或错误信息
     * </p>
     *
     * @param taskId 待执行的任务 ID
     */
    @Async("aiTaskExecutor")
    public void execute(String taskId) {
        AiTaskEntity task = aiTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        if (TaskStatus.valueOf(task.getStatus()) != TaskStatus.PENDING) {
            return;
        }
        Instant startTime = Instant.now();
        task.setStatus(TaskStatus.RUNNING.name());
        task.setStartedAt(startTime);
        task.setUpdatedAt(startTime);
        aiTaskRepository.save(task);
        try {
            RequirementResult analysis = aiServiceClient.parseRequirement(task.getRequirement());
            if (isCancelled(taskId)) {
                return;
            }
            GenerateResult generation = aiServiceClient.generateProject(analysis);
            DemoGenerateResult result = new DemoGenerateResult(analysis, generation);
            Instant finishTime = Instant.now();
            AiTaskEntity latest = aiTaskRepository.findById(taskId).orElse(null);
            if (latest == null || TaskStatus.valueOf(latest.getStatus()) != TaskStatus.RUNNING) {
                return;
            }
            latest.setStatus(TaskStatus.SUCCEEDED.name());
            latest.setResultJson(writeJson(result));
            latest.setErrorMessage(null);
            latest.setFinishedAt(finishTime);
            latest.setUpdatedAt(finishTime);
            aiTaskRepository.save(latest);
        } catch (Exception ex) {
            if (isCancelled(taskId)) {
                return;
            }
            Instant finishTime = Instant.now();
            AiTaskEntity latest = aiTaskRepository.findById(taskId).orElse(null);
            if (latest == null || TaskStatus.valueOf(latest.getStatus()) != TaskStatus.RUNNING) {
                return;
            }
            latest.setStatus(TaskStatus.FAILED.name());
            latest.setErrorMessage(ex.getMessage());
            latest.setFinishedAt(finishTime);
            latest.setUpdatedAt(finishTime);
            aiTaskRepository.save(latest);
        }
    }

    private boolean isCancelled(String taskId) {
        return aiTaskRepository.findById(taskId)
                .map(entity -> TaskStatus.valueOf(entity.getStatus()) == TaskStatus.CANCELLED)
                .orElse(true);
    }

    private String writeJson(DemoGenerateResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("任务结果序列化失败", ex);
        }
    }
}
