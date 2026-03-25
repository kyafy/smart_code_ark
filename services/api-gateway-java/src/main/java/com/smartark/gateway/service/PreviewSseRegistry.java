package com.smartark.gateway.service;

import com.smartark.gateway.dto.TaskPreviewResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class PreviewSseRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PreviewSseRegistry.class);

    private final ConcurrentHashMap<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void register(String taskId, SseEmitter emitter) {
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));
    }

    public void broadcast(String taskId, TaskPreviewResult payload) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters == null || taskEmitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : taskEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("preview-status")
                        .data(payload));
            } catch (IOException e) {
                logger.debug("Failed to send SSE event for task {}, removing emitter", taskId);
                remove(taskId, emitter);
            }
        }
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> taskEmitters = emitters.get(taskId);
        if (taskEmitters != null) {
            taskEmitters.remove(emitter);
            if (taskEmitters.isEmpty()) {
                emitters.remove(taskId);
            }
        }
    }
}
