package com.smartark.gateway.controller;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.common.response.PageResponse;
import com.smartark.gateway.dto.CreateProjectRequest;
import com.smartark.gateway.dto.ProjectResult;
import com.smartark.gateway.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目管理控制器
 * <p>
 * 处理项目相关的 HTTP 请求，包括创建、列表查询和详情查询。
 * 对应 RESTful 风格的 /api/v1/projects 路径。
 * </p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService projectService;

    /**
     * 构造函数
     *
     * @param projectService 项目服务
     */
    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 创建项目
     *
     * @param request 创建项目请求体
     * @return 创建成功的项目信息
     */
    @PostMapping
    public ApiResponse<ProjectResult> create(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.success(projectService.create(RequestContext.getUserId(), request));
    }

    /**
     * 分页查询项目列表
     *
     * @param page 页码（默认1）
     * @param size 每页数量（默认10）
     * @return 项目列表的分页结果
     */
    @GetMapping
    public ApiResponse<PageResponse<ProjectResult>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.success(projectService.listByOwner(RequestContext.getUserId(), page, size));
    }

    /**
     * 查询项目详情
     *
     * @param projectId 项目 ID
     * @return 项目详细信息
     */
    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResult> detail(@PathVariable String projectId) {
        return ApiResponse.success(projectService.detail(RequestContext.getUserId(), projectId));
    }
}
