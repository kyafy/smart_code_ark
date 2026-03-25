package com.smartark.gateway.service;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class PreviewRouteRegistry {
    private final ConcurrentMap<String, RouteEntry> routeTable = new ConcurrentHashMap<>();

    public void register(String taskId, String upstreamBaseUrl, LocalDateTime expireAt) {
        if (taskId == null || taskId.isBlank() || upstreamBaseUrl == null || upstreamBaseUrl.isBlank()) {
            return;
        }
        routeTable.put(taskId, new RouteEntry(taskId, upstreamBaseUrl, expireAt, LocalDateTime.now()));
    }

    public Optional<RouteEntry> resolve(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        RouteEntry entry = routeTable.get(taskId);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry, LocalDateTime.now())) {
            routeTable.remove(taskId, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public void unregister(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        routeTable.remove(taskId);
    }

    public int recycleExpired(LocalDateTime now) {
        LocalDateTime baseline = now == null ? LocalDateTime.now() : now;
        int[] removed = {0};
        routeTable.forEach((taskId, entry) -> {
            if (isExpired(entry, baseline) && routeTable.remove(taskId, entry)) {
                removed[0]++;
            }
        });
        return removed[0];
    }

    int size() {
        return routeTable.size();
    }

    private boolean isExpired(RouteEntry entry, LocalDateTime now) {
        return entry.expireAt() != null && entry.expireAt().isBefore(now);
    }

    public record RouteEntry(
            String taskId,
            String upstreamBaseUrl,
            LocalDateTime expireAt,
            LocalDateTime updatedAt
    ) {
    }
}
