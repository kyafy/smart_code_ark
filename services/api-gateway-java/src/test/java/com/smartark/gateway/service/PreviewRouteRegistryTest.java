package com.smartark.gateway.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewRouteRegistryTest {

    @Test
    void registerAndResolve_shouldReturnActiveRoute() {
        PreviewRouteRegistry registry = new PreviewRouteRegistry();
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(10);

        registry.register("task-a", "http://localhost:30001", expireAt);
        var resolved = registry.resolve("task-a");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().upstreamBaseUrl()).isEqualTo("http://localhost:30001");
    }

    @Test
    void resolve_shouldEvictExpiredRoute() {
        PreviewRouteRegistry registry = new PreviewRouteRegistry();
        LocalDateTime expireAt = LocalDateTime.now().minusSeconds(1);
        registry.register("task-expired", "http://localhost:30002", expireAt);

        assertThat(registry.resolve("task-expired")).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void recycleExpired_shouldRemoveOnlyExpiredRoutes() {
        PreviewRouteRegistry registry = new PreviewRouteRegistry();
        LocalDateTime now = LocalDateTime.now();
        registry.register("task-1", "http://localhost:30011", now.minusMinutes(1));
        registry.register("task-2", "http://localhost:30012", now.plusMinutes(5));
        registry.register("task-3", "http://localhost:30013", now.minusMinutes(2));

        int removed = registry.recycleExpired(now);

        assertThat(removed).isEqualTo(2);
        assertThat(registry.resolve("task-2")).isPresent();
        assertThat(registry.resolve("task-1")).isEmpty();
        assertThat(registry.resolve("task-3")).isEmpty();
    }

    @Test
    void unregister_shouldDeleteRoute() {
        PreviewRouteRegistry registry = new PreviewRouteRegistry();
        registry.register("task-x", "http://localhost:30021", LocalDateTime.now().plusMinutes(1));

        registry.unregister("task-x");

        assertThat(registry.resolve("task-x")).isEmpty();
    }
}
