package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/paper")
public class PaperController {
    private final TaskService taskService;

    public PaperController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/outline")
    public ApiResponse<PaperOutlineGenerateResult> generateOutline(@Valid @RequestBody PaperOutlineGenerateRequest request) {
        return ApiResponse.success(taskService.generatePaperOutline(request));
    }

    @GetMapping("/outline/{taskId}")
    public ApiResponse<PaperOutlineResult> getOutline(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPaperOutline(taskId));
    }
}
