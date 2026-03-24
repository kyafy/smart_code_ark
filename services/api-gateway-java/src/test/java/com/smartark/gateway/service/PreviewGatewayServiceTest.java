package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewGatewayServiceTest {

    @Mock
    private PreviewRouteRegistry previewRouteRegistry;
    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private TaskLogRepository taskLogRepository;

    private PreviewGatewayService previewGatewayService;

    @BeforeEach
    void setUp() {
        previewGatewayService = new PreviewGatewayService(previewRouteRegistry, taskPreviewRepository, taskLogRepository);
        ReflectionTestUtils.setField(previewGatewayService, "previewGatewayEnabled", true);
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void registerRoute_shouldUseGatewayPathWhenEnabled() {
        LocalDateTime expireAt = LocalDateTime.now().plusHours(1);

        String url = previewGatewayService.registerRoute("task1", 30001, expireAt);

        assertThat(url).isEqualTo("/p/task1/");
        verify(previewRouteRegistry).register("task1", "http://localhost:30001", expireAt);
    }

    @Test
    void registerRoute_shouldFallbackToLegacyPathWhenDisabled() {
        ReflectionTestUtils.setField(previewGatewayService, "previewGatewayEnabled", false);

        String url = previewGatewayService.registerRoute("task2", 30002, LocalDateTime.now().plusHours(1));

        assertThat(url).isEqualTo("/api/preview/task2/");
        verify(previewRouteRegistry, never()).register(any(), any(), any());
    }

    @Test
    void resolveRoute_shouldReturnRegistryEntryFirst() {
        PreviewRouteRegistry.RouteEntry entry = new PreviewRouteRegistry.RouteEntry(
                "task3", "http://localhost:30003", LocalDateTime.now().plusMinutes(5), LocalDateTime.now());
        when(previewRouteRegistry.resolve("task3")).thenReturn(Optional.of(entry));

        Optional<PreviewRouteRegistry.RouteEntry> route = previewGatewayService.resolveRoute("task3");

        assertThat(route).isPresent();
        assertThat(route.get().upstreamBaseUrl()).isEqualTo("http://localhost:30003");
        verify(taskPreviewRepository, never()).findByTaskId(any());
    }

    @Test
    void resolveRoute_shouldRestoreFromTaskPreviewWhenRegistryMissed() {
        when(previewRouteRegistry.resolve("task4")).thenReturn(Optional.empty())
                .thenReturn(Optional.of(new PreviewRouteRegistry.RouteEntry(
                        "task4", "http://localhost:30004", LocalDateTime.now().plusHours(1), LocalDateTime.now()
                )));

        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setTaskId("task4");
        preview.setStatus("ready");
        preview.setHostPort(30004);
        preview.setExpireAt(LocalDateTime.now().plusHours(1));
        when(taskPreviewRepository.findByTaskId("task4")).thenReturn(Optional.of(preview));

        Optional<PreviewRouteRegistry.RouteEntry> route = previewGatewayService.resolveRoute("task4");

        assertThat(route).isPresent();
        verify(previewRouteRegistry).register("task4", "http://localhost:30004", preview.getExpireAt());
    }

    @Test
    void recycleExpiredRoutes_shouldSkipWhenDisabled() {
        ReflectionTestUtils.setField(previewGatewayService, "previewGatewayEnabled", false);

        int removed = previewGatewayService.recycleExpiredRoutes();

        assertThat(removed).isZero();
        verify(previewRouteRegistry, never()).recycleExpired(any());
    }
}
