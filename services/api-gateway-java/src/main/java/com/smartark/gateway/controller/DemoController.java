package com.smartark.gateway.controller;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.common.response.PageResponse;
import com.smartark.gateway.dto.AiTaskResult;
import com.smartark.gateway.dto.AiTaskSubmitResult;
import com.smartark.gateway.dto.DemoGenerateRequest;
import com.smartark.gateway.service.AiTaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 演示与任务管理控制器
 * <p>
 * 提供 AI 生成任务的提交、查询、取消、重试等接口。
 * 同时包含服务健康检查接口。
 * </p>
 */
@RestController
@RequestMapping("/api/v1")
public class DemoController {

    private final AiTaskService aiTaskService;

    /**
     * 构造函数
     *
     * @param aiTaskService AI 任务服务
     */
    public DemoController(AiTaskService aiTaskService) {
        this.aiTaskService = aiTaskService;
    }

    /**
     * 服务健康检查
     *
     * @return 服务状态信息
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("service", "api-gateway", "status", "UP"));
    }

    /**
     * 提交 AI 生成任务
     *
     * @param request 任务生成请求，包含需求描述
     * @return 提交结果，包含任务 ID
     */
    @PostMapping("/demo/generate")
    public ApiResponse<AiTaskSubmitResult> generate(@Valid @RequestBody DemoGenerateRequest request) {
        return ApiResponse.success(aiTaskService.submit(RequestContext.getUserId(), request.requirement()));
    }

    /**
     * 查询任务详情
     *
     * @param taskId 任务 ID
     * @return 任务详细信息，包括状态和结果
     */
    @GetMapping("/demo/tasks/{taskId}")
    public ApiResponse<AiTaskResult> taskDetail(@PathVariable String taskId) {
        return ApiResponse.success(aiTaskService.detail(RequestContext.getUserId(), taskId));
    }

    /**
     * 取消任务
     *
     * @param taskId 任务 ID
     * @return 取消后的任务信息
     */
    @PostMapping("/demo/tasks/{taskId}/cancel")
    public ApiResponse<AiTaskResult> cancel(@PathVariable String taskId) {
        return ApiResponse.success(aiTaskService.cancel(RequestContext.getUserId(), taskId));
    }

    /**
     * 重试任务
     *
     * @param taskId 任务 ID
     * @return 重试提交结果
     */
    @PostMapping("/demo/tasks/{taskId}/retry")
    public ApiResponse<AiTaskSubmitResult> retry(@PathVariable String taskId) {
        return ApiResponse.success(aiTaskService.retry(RequestContext.getUserId(), taskId));
    }

    /**
     * 分页查询任务列表
     *
     * @param page 页码（默认1）
     * @param size 每页数量（默认10）
     * @return 任务列表分页结果
     */
    @GetMapping("/demo/tasks")
    public ApiResponse<PageResponse<AiTaskResult>> taskList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.success(aiTaskService.list(RequestContext.getUserId(), page, size));
    }
}
