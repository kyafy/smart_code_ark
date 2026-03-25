package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.ProjectConfirmRequest;
import com.smartark.gateway.dto.ProjectConfirmResult;
import com.smartark.gateway.dto.ProjectDetail;
import com.smartark.gateway.dto.ProjectSummary;
import com.smartark.gateway.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/projects", "/api/v1/projects"})
@Tag(name = "Project", description = "Project confirmation and project management APIs")
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping("/confirm")
    @Operation(summary = "Confirm project from chat/design session")
    public ApiResponse<ProjectConfirmResult> confirm(@Valid @RequestBody ProjectConfirmRequest request) {
        return ApiResponse.success(projectService.confirm(request));
    }

    @GetMapping
    @Operation(summary = "List projects")
    public ApiResponse<List<ProjectSummary>> list() {
        return ApiResponse.success(projectService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get project detail")
    public ApiResponse<ProjectDetail> detail(
            @Parameter(description = "Project ID", required = true) @PathVariable("id") String id) {
        return ApiResponse.success(projectService.detail(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete project")
    public ApiResponse<Boolean> delete(
            @Parameter(description = "Project ID", required = true) @PathVariable("id") String id) {
        return ApiResponse.success(projectService.delete(id));
    }
}
