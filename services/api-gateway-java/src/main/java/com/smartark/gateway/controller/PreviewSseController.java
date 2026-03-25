package com.smartark.gateway.controller;

import com.smartark.gateway.service.PreviewSseRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
@Tag(name = "Preview SSE", description = "Preview status Server-Sent Events APIs")
public class PreviewSseController {

    private final PreviewSseRegistry sseRegistry;

    public PreviewSseController(PreviewSseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    @GetMapping(value = "/task/{taskId}/preview/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Subscribe preview status events",
            description = "SSE stream for task preview lifecycle events.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "SSE stream",
                            content = @Content(mediaType = "text/event-stream")
                    )
            }
    )
    public SseEmitter previewEvents(
            @Parameter(description = "Task ID", required = true) @PathVariable("taskId") String taskId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 minutes timeout
        sseRegistry.register(taskId, emitter);
        return emitter;
    }
}
