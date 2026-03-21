package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
<<<<<<< HEAD
=======
import com.smartark.gateway.dto.PreviewLogsResult;
>>>>>>> origin/master
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
<<<<<<< HEAD
=======
import org.springframework.web.bind.annotation.RequestParam;
>>>>>>> origin/master
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

    @PostMapping("/task/{taskId}/preview/rebuild")
    public ApiResponse<TaskPreviewResult> rebuildPreview(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.rebuildPreview(taskId));
    }

<<<<<<< HEAD
=======
    @GetMapping("/task/{taskId}/preview/logs")
    public ApiResponse<PreviewLogsResult> getPreviewLogs(
            @PathVariable("taskId") String taskId,
            @RequestParam(value = "tail", defaultValue = "200") int tail) {
        return ApiResponse.success(taskService.getPreviewLogs(taskId, tail));
    }

>>>>>>> origin/master
    @PostMapping("/task/{taskId}/modify")
    public ApiResponse<GenerateResult> modify(@PathVariable("taskId") String taskId, @RequestBody TaskModifyRequest request) {
        return ApiResponse.success(taskService.modify(taskId, request));
    }

    @PostMapping("/task/{taskId}/cancel")
    public ApiResponse<GenerateResult> cancel(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.cancelTask(taskId));
    }

    @PostMapping("/task/{taskId}/retry/{stepCode}")
    public ApiResponse<GenerateResult> retryStep(@PathVariable("taskId") String taskId, @PathVariable("stepCode") String stepCode) {
        return ApiResponse.success(taskService.retryStep(taskId, stepCode));
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
