package com.smartark.gateway.controller;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import com.smartark.gateway.dto.PaperProjectSummary;
import com.smartark.gateway.dto.PaperTraceabilityResult;
import com.smartark.gateway.dto.RagReindexRequest;
import com.smartark.gateway.dto.RagRetrievalResult;
import com.smartark.gateway.dto.RagStatsResult;
import com.smartark.gateway.dto.TopicAdoptRequest;
import com.smartark.gateway.dto.TopicSuggestRequest;
import com.smartark.gateway.dto.TopicSuggestResult;
import com.smartark.gateway.service.RagService;
import com.smartark.gateway.service.TaskService;
import com.smartark.gateway.service.TopicSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/paper")
@Tag(name = "Paper", description = "Paper outline/manuscript generation, topic workflow, and RAG retrieval APIs")
public class PaperController {
    private final TaskService taskService;
    private final RagService ragService;
    private final TopicSuggestionService topicSuggestionService;

    public PaperController(TaskService taskService,
                           RagService ragService,
                           TopicSuggestionService topicSuggestionService) {
        this.taskService = taskService;
        this.ragService = ragService;
        this.topicSuggestionService = topicSuggestionService;
    }

    @PostMapping("/outline")
    @Operation(summary = "Generate paper outline task", description = "Create a new paper-outline generation task.")
    public ApiResponse<PaperOutlineGenerateResult> generateOutline(@Valid @RequestBody PaperOutlineGenerateRequest request) {
        return ApiResponse.success(taskService.generatePaperOutline(request));
    }

    @GetMapping("/outline/{taskId}")
    @Operation(summary = "Get paper outline by taskId")
    public ApiResponse<PaperOutlineResult> getOutline(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPaperOutline(taskId));
    }

    @GetMapping("/manuscript/{taskId}")
    @Operation(summary = "Get manuscript by taskId")
    public ApiResponse<PaperManuscriptResult> getManuscript(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        return ApiResponse.success(taskService.getPaperManuscript(taskId));
    }

    @GetMapping("/list")
    @Operation(summary = "List paper projects")
    public ApiResponse<List<PaperProjectSummary>> listPaperProjects() {
        return ApiResponse.success(taskService.listPaperProjects());
    }

    @PostMapping("/rag/reindex")
    @Operation(summary = "Reindex RAG session", description = "Rebuilds vector index for a session. If sessionId is empty, no operation is performed.")
    public ApiResponse<Void> ragReindex(@RequestBody(required = false) RagReindexRequest request) {
        Long sessionId = request != null ? request.sessionId() : null;
        if (sessionId != null) {
            ragService.reindex(sessionId);
        }
        return ApiResponse.success(null);
    }

    @GetMapping("/rag/retrieval/{taskId}")
    @Operation(summary = "Get RAG retrieval result by taskId")
    public ApiResponse<RagRetrievalResult> ragRetrieval(@PathVariable("taskId") String taskId,
                                                        @Parameter(description = "Whether to return reranked result", required = false)
                                                        @RequestParam(value = "reranked", defaultValue = "false") boolean reranked) {
        RagRetrievalResult result = taskService.getRagRetrieval(taskId, reranked);
        return ApiResponse.success(result);
    }

    @GetMapping("/rag/stats")
    @Operation(summary = "Get RAG index stats")
    public ApiResponse<RagStatsResult> ragStats() {
        return ApiResponse.success(ragService.getStats());
    }

    @PostMapping("/topic/suggest")
    @Operation(summary = "Suggest paper topics")
    public ApiResponse<TopicSuggestResult> suggestTopics(@Valid @RequestBody TopicSuggestRequest request) {
        String userId = RequestContext.getUserId();
        return ApiResponse.success(topicSuggestionService.suggest(request, userId));
    }

    @PostMapping("/topic/adopt")
    @Operation(summary = "Adopt a suggested topic", description = "Adopts selected topic and converts it into outline input payload.")
    public ApiResponse<PaperOutlineGenerateRequest> adoptTopic(@Valid @RequestBody TopicAdoptRequest request) {
        String userId = RequestContext.getUserId();
        PaperTopicSessionEntity session = topicSuggestionService.adopt(request, userId);
        return ApiResponse.success(new PaperOutlineGenerateRequest(
                session.getTopic(),
                session.getDiscipline(),
                session.getDegreeLevel(),
                session.getMethodPreference()
        ));
    }

    @GetMapping("/traceability/{taskId}")
    @Operation(summary = "Get citation traceability report")
    public ApiResponse<PaperTraceabilityResult> getTraceability(
            @Parameter(description = "Task ID", required = true) @PathVariable String taskId) {
        return ApiResponse.success(taskService.getPaperTraceability(taskId));
    }
}
