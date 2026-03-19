package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.TaskModifyRequest;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.dto.TaskStatusResult;
import com.smartark.gateway.service.TaskService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/generate")
    public ApiResponse<GenerateResult> generate(@RequestBody GenerateRequest request) {
        return ApiResponse.success(taskService.generate(request));
    }

    @GetMapping("/task/{taskId}/status")
    public ApiResponse<TaskStatusResult> getStatus(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getStatus(taskId));
    }

    @GetMapping("/task/{taskId}/preview")
    public ApiResponse<TaskPreviewResult> getPreview(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPreview(taskId));
    }

    @PostMapping("/task/{taskId}/modify")
    public ApiResponse<GenerateResult> modify(@PathVariable("taskId") String taskId, @RequestBody TaskModifyRequest request) {
        return ApiResponse.success(taskService.modify(taskId, request));
    }

    @GetMapping(value = "/task/{taskId}/download", produces = "application/zip")
    public ResponseEntity<byte[]> download(@PathVariable("taskId") String taskId) {
        byte[] data = taskService.getDownload(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"smartark_" + taskId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(data);
    }

    @GetMapping("/task/{taskId}/logs")
    public ApiResponse<java.util.List<com.smartark.gateway.dto.TaskLogDto>> getLogs(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getLogs(taskId));
    }
}
