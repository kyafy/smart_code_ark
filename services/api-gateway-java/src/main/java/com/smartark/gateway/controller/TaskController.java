package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.ContractReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.dto.DeliveryValidateRequest;
import com.smartark.gateway.dto.GenerateRequest;
import com.smartark.gateway.dto.GenerateResult;
import com.smartark.gateway.dto.PreviewLogsResult;
import com.smartark.gateway.dto.TaskModifyRequest;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.dto.TaskStatusResult;
import com.smartark.gateway.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Task", description = "Code generation task lifecycle, delivery checks, preview and artifact APIs")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping("/generate")
    @Operation(summary = "Create generation task")
    public ApiResponse<GenerateResult> generate(@RequestBody GenerateRequest request) {
        return ApiResponse.success(taskService.generate(request));
    }

    @GetMapping("/task/{taskId}/status")
    @Operation(summary = "Get task status")
    public ApiResponse<TaskStatusResult> getStatus(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getStatus(taskId));
    }

    @GetMapping("/task/{taskId}/preview")
    @Operation(summary = "Get task preview deployment info")
    public ApiResponse<TaskPreviewResult> getPreview(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPreview(taskId));
    }

    @GetMapping("/task/{taskId}/contract-report")
    @Operation(summary = "Get contract validation report")
    public ApiResponse<ContractReportResult> getContractReport(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getContractReport(taskId));
    }

    @GetMapping("/task/{taskId}/delivery-report")
    @Operation(summary = "Get delivery quality report")
    public ApiResponse<DeliveryReportResult> getDeliveryReport(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getDeliveryReport(taskId));
    }

    @PostMapping("/task/{taskId}/delivery/validate")
    @Operation(summary = "Validate delivery output", description = "Runs delivery validation; optionally enables auto-fix.")
    public ApiResponse<ContractReportResult> validateDelivery(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestBody(required = false) DeliveryValidateRequest request) {
        return ApiResponse.success(taskService.validateDelivery(taskId, request == null ? null : request.autoFix()));
    }

    @PostMapping("/task/{taskId}/preview/rebuild")
    @Operation(summary = "Rebuild task preview environment")
    public ApiResponse<TaskPreviewResult> rebuildPreview(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.rebuildPreview(taskId));
    }

    @GetMapping("/task/{taskId}/preview/logs")
    @Operation(summary = "Get preview logs")
    public ApiResponse<PreviewLogsResult> getPreviewLogs(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @Parameter(description = "Tail line count", required = false)
            @RequestParam(value = "tail", defaultValue = "200") int tail) {
        return ApiResponse.success(taskService.getPreviewLogs(taskId, tail));
    }

    @PostMapping("/task/{taskId}/modify")
    @Operation(summary = "Modify and regenerate task artifacts")
    public ApiResponse<GenerateResult> modify(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @RequestBody TaskModifyRequest request) {
        return ApiResponse.success(taskService.modify(taskId, request));
    }

    @PostMapping("/task/{taskId}/cancel")
    @Operation(summary = "Cancel task")
    public ApiResponse<GenerateResult> cancel(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.cancelTask(taskId));
    }

    @PostMapping("/task/{taskId}/retry/{stepCode}")
    @Operation(summary = "Retry failed step")
    public ApiResponse<GenerateResult> retryStep(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId,
            @Parameter(description = "Step code, for example codegen_backend", required = true) @PathVariable("stepCode") String stepCode) {
        return ApiResponse.success(taskService.retryStep(taskId, stepCode));
    }

    @GetMapping(value = "/task/{taskId}/download", produces = "application/zip")
    @Operation(
            summary = "Download task artifact as ZIP",
            description = "Returns the generated project package.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "ZIP file",
                            content = @Content(mediaType = "application/zip")
                    )
            }
    )
    public ResponseEntity<byte[]> download(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        byte[] data = taskService.getDownload(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"smartark_" + taskId + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(data);
    }

    @GetMapping("/task/{taskId}/logs")
    @Operation(summary = "Get task execution logs")
    public ApiResponse<java.util.List<com.smartark.gateway.dto.TaskLogDto>> getLogs(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getLogs(taskId));
    }
}
