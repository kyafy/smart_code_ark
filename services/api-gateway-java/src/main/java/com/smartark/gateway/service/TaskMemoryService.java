package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class TaskMemoryService {
    private static final Logger logger = LoggerFactory.getLogger(TaskMemoryService.class);

    private final ObjectMapper objectMapper;
    private final String workspaceRoot;

    public TaskMemoryService(
            ObjectMapper objectMapper,
            @Value("${smartark.agent.workspace-root:/tmp/smartark/}") String workspaceRoot
    ) {
        this.objectMapper = objectMapper;
        this.workspaceRoot = workspaceRoot;
    }

    public synchronized List<MemoryEntry> readRecent(String taskId, int limit) {
        int safeLimit = Math.max(1, limit);
        Path file = checkpointFile(taskId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return List.of();
            }
            List<MemoryEntry> entries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(objectMapper.readValue(line, MemoryEntry.class));
                } catch (Exception parseError) {
                    logger.warn("Ignore invalid memory checkpoint line for task={}, error={}", taskId, parseError.getMessage());
                }
            }
            if (entries.size() <= safeLimit) {
                return entries;
            }
            return entries.subList(entries.size() - safeLimit, entries.size());
        } catch (IOException e) {
            logger.warn("Read memory checkpoint failed for task={}, error={}", taskId, e.getMessage());
            return List.of();
        }
    }

    public synchronized void append(String taskId,
                                    String stepCode,
                                    int sequence,
                                    String promptSummary,
                                    String outputSummary,
                                    String failureReason,
                                    List<String> fixedActions) {
        Path file = checkpointFile(taskId);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            MemoryEntry entry = new MemoryEntry(
                    taskId,
                    stepCode,
                    Math.max(sequence, 1),
                    safeText(promptSummary),
                    safeText(outputSummary),
                    safeText(failureReason),
                    fixedActions == null ? List.of() : List.copyOf(fixedActions),
                    LocalDateTime.now().toString()
            );
            String line = objectMapper.writeValueAsString(entry);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Write memory checkpoint failed for task={}, stepCode={}, error={}", taskId, stepCode, e.getMessage());
        }
    }

    private Path checkpointFile(String taskId) {
        return Paths.get(workspaceRoot, taskId, ".smartark", "checkpoints.jsonl").toAbsolutePath();
    }

    private String safeText(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        int max = 500;
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public record MemoryEntry(
            String taskId,
            String stepCode,
            int sequence,
            String promptSummary,
            String outputSummary,
            String failureReason,
            List<String> fixedActions,
            String createdAt
    ) {
        public MemoryEntry {
            fixedActions = fixedActions == null ? Collections.emptyList() : fixedActions;
        }
    }
}
