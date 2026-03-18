package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.ProjectConfirmRequest;
import com.smartark.gateway.dto.ProjectConfirmResult;
import com.smartark.gateway.dto.ProjectDetail;
import com.smartark.gateway.dto.ProjectSummary;
import com.smartark.gateway.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/projects", "/api/v1/projects"})
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/confirm")
    public ApiResponse<ProjectConfirmResult> confirm(@Valid @RequestBody ProjectConfirmRequest request) {
        return ApiResponse.success(projectService.confirm(request));
    }

    @GetMapping
    public ApiResponse<List<ProjectSummary>> list() {
        return ApiResponse.success(projectService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectDetail> detail(@PathVariable("id") String id) {
        return ApiResponse.success(projectService.detail(id));
    }
}
