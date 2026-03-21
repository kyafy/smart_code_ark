package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.dto.PaperProjectSummary;
import com.smartark.gateway.dto.RagReindexRequest;
import com.smartark.gateway.dto.RagRetrievalResult;
import com.smartark.gateway.dto.RagStatsResult;
import com.smartark.gateway.service.RagService;
import com.smartark.gateway.service.TaskService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/paper")
public class PaperController {
    private final TaskService taskService;
    private final RagService ragService;

    public PaperController(TaskService taskService, RagService ragService) {
        this.taskService = taskService;
        this.ragService = ragService;
    }

    @PostMapping("/outline")
    public ApiResponse<PaperOutlineGenerateResult> generateOutline(@Valid @RequestBody PaperOutlineGenerateRequest request) {
        return ApiResponse.success(taskService.generatePaperOutline(request));
    }

    @GetMapping("/outline/{taskId}")
    public ApiResponse<PaperOutlineResult> getOutline(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPaperOutline(taskId));
    }

    @GetMapping("/manuscript/{taskId}")
    public ApiResponse<PaperManuscriptResult> getManuscript(@PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPaperManuscript(taskId));
    }

    @GetMapping("/list")
    public ApiResponse<List<PaperProjectSummary>> listPaperProjects() {
        return ApiResponse.success(taskService.listPaperProjects());
    }

    @PostMapping("/rag/reindex")
    public ApiResponse<Void> ragReindex(@RequestBody(required = false) RagReindexRequest request) {
        Long sessionId = request != null ? request.sessionId() : null;
        if (sessionId != null) {
            ragService.reindex(sessionId);
        }
        return ApiResponse.success(null);
    }

    @GetMapping("/rag/retrieval/{taskId}")
    public ApiResponse<RagRetrievalResult> ragRetrieval(@PathVariable("taskId") String taskId) {
        RagRetrievalResult result = taskService.getRagRetrieval(taskId);
        return ApiResponse.success(result);
    }

    @GetMapping("/rag/stats")
    public ApiResponse<RagStatsResult> ragStats() {
        return ApiResponse.success(ragService.getStats());
    }
}
