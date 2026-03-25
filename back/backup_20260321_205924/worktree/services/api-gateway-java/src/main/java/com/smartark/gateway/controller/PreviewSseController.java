package com.smartark.gateway.controller;

import com.smartark.gateway.service.PreviewSseRegistry;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class PreviewSseController {

    private final PreviewSseRegistry sseRegistry;

    public PreviewSseController(PreviewSseRegistry sseRegistry) {
        this.sseRegistry = sseRegistry;
    }

    @GetMapping(value = "/task/{taskId}/preview/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter previewEvents(@PathVariable("taskId") String taskId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5 minutes timeout
        sseRegistry.register(taskId, emitter);
        return emitter;
    }
}
