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
import java.util.List;

@Service
public class LongTermMemoryService {
    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryService.class);

    private final ObjectMapper objectMapper;
    private final String workspaceRoot;

    public LongTermMemoryService(
            ObjectMapper objectMapper,
            @Value("${smartark.agent.workspace-root:/tmp/smartark/}") String workspaceRoot
    ) {
        this.objectMapper = objectMapper;
        this.workspaceRoot = workspaceRoot;
    }

    public synchronized List<MemoryItem> readTopK(String projectId, Long userId, String stackSignature, int topK) {
        int safeTopK = Math.max(1, topK);
        Path file = memoryFile(projectId, userId, stackSignature);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<MemoryItem> items = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    items.add(objectMapper.readValue(line, MemoryItem.class));
                } catch (Exception e) {
                    logger.warn("Ignore invalid long-term memory line projectId={}, userId={}, error={}",
                            projectId, userId, e.getMessage());
                }
            }
            if (items.size() <= safeTopK) {
                return items;
            }
            return items.subList(items.size() - safeTopK, items.size());
        } catch (IOException e) {
            logger.warn("Read long-term memory failed projectId={}, userId={}, error={}", projectId, userId, e.getMessage());
            return List.of();
        }
    }

    public synchronized void append(String projectId,
                                    Long userId,
                                    String stackSignature,
                                    String taskId,
                                    String stepCode,
                                    String memoryType,
                                    String summary,
                                    List<String> fixedActions) {
        Path file = memoryFile(projectId, userId, stackSignature);
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            MemoryItem item = new MemoryItem(
                    projectId,
                    userId,
                    stackSignature,
                    taskId,
                    stepCode,
                    memoryType,
                    safeText(summary),
                    fixedActions == null ? List.of() : List.copyOf(fixedActions),
                    LocalDateTime.now().toString()
            );
            String line = objectMapper.writeValueAsString(item);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Write long-term memory failed projectId={}, userId={}, stepCode={}, error={}",
                    projectId, userId, stepCode, e.getMessage());
        }
    }

    private Path memoryFile(String projectId, Long userId, String stackSignature) {
        String projectPart = sanitizeFileToken(projectId);
        String userPart = userId == null ? "0" : String.valueOf(userId);
        String stackPart = sanitizeFileToken(stackSignature);
        return Paths.get(workspaceRoot, ".smartark", "longterm", projectPart + "__" + userPart + "__" + stackPart + ".jsonl")
                .toAbsolutePath();
    }

    private String sanitizeFileToken(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        return input.trim().replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private String safeText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "";
        }
        int max = 600;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    public record MemoryItem(
            String projectId,
            Long userId,
            String stackSignature,
            String taskId,
            String stepCode,
            String memoryType,
            String summary,
            List<String> fixedActions,
            String createdAt
    ) {
    }
}
