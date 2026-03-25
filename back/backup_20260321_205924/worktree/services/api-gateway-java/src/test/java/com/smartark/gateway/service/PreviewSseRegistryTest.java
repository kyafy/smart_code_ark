package com.smartark.gateway.service;

import com.smartark.gateway.dto.TaskPreviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PreviewSseRegistryTest {

    private PreviewSseRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PreviewSseRegistry();
    }

    @Test
    void register_shouldAcceptEmitter() {
        SseEmitter emitter = new SseEmitter();
        assertThatNoException().isThrownBy(() -> registry.register("t1", emitter));
    }

    @Test
    void broadcast_shouldSendEventToRegisteredEmitters() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        registry.register("t1", emitter);

        TaskPreviewResult result = new TaskPreviewResult("t1", "provisioning", "install_deps",
                null, null, null, null, null);

        registry.broadcast("t1", result);

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void broadcast_shouldNotFailForUnregisteredTaskId() {
        TaskPreviewResult result = new TaskPreviewResult("no-task", "ready", null,
                "http://localhost:30000", null, null, null, null);

        assertThatNoException().isThrownBy(() -> registry.broadcast("no-task", result));
    }

    @Test
    void broadcast_shouldSendToMultipleEmitters() throws IOException {
        SseEmitter emitter1 = mock(SseEmitter.class);
        SseEmitter emitter2 = mock(SseEmitter.class);
        registry.register("t2", emitter1);
        registry.register("t2", emitter2);

        TaskPreviewResult result = new TaskPreviewResult("t2", "ready", null,
                "http://localhost:30001", null, null, null, null);

        registry.broadcast("t2", result);

        verify(emitter1).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter2).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void broadcast_shouldRemoveEmitterOnIOException() throws IOException {
        SseEmitter brokenEmitter = mock(SseEmitter.class);
        doThrow(new IOException("broken pipe")).when(brokenEmitter).send(any(SseEmitter.SseEventBuilder.class));
        registry.register("t3", brokenEmitter);

        TaskPreviewResult result = new TaskPreviewResult("t3", "provisioning", "boot_service",
                null, null, null, null, null);

        // First broadcast removes the broken emitter
        registry.broadcast("t3", result);

        // Second broadcast should not try the broken emitter again
        registry.broadcast("t3", result);

        // Only called once (first time, then removed)
        verify(brokenEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void broadcast_shouldIsolateTaskIds() throws IOException {
        SseEmitter emitterA = mock(SseEmitter.class);
        SseEmitter emitterB = mock(SseEmitter.class);
        registry.register("taskA", emitterA);
        registry.register("taskB", emitterB);

        TaskPreviewResult result = new TaskPreviewResult("taskA", "ready", null,
                "url", null, null, null, null);

        registry.broadcast("taskA", result);

        verify(emitterA).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitterB, never()).send(any(SseEmitter.SseEventBuilder.class));
    }
}
