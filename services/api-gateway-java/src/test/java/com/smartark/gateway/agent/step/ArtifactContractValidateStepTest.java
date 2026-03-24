package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactContractValidateStepTest {

    @TempDir
    Path tempDir;

    @Test
    void execute_passesWithCompleteArtifact() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        AgentExecutionContext context = buildContext(workspace);

        step.execute(context);

        assertThat(context.getContractViolations()).isEmpty();
    }

    @Test
    void execute_detectsMissingReadme() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        Files.delete(workspace.resolve("README.md"));
        AgentExecutionContext context = buildContext(workspace);

        step.execute(context);

        assertThat(context.getContractViolations())
                .anyMatch(v -> v.contains("README.md"));
    }

    @Test
    void execute_detectsInvalidComposeContext() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        Files.writeString(workspace.resolve("docker-compose.yml"),
                "services:\n" +
                        "  backend:\n" +
                        "    build:\n" +
                        "      context: ./nonexistent-dir\n",
                StandardCharsets.UTF_8);
        AgentExecutionContext context = buildContext(workspace);

        step.execute(context);

        assertThat(context.getContractViolations())
                .anyMatch(v -> v.contains("nonexistent-dir") && v.contains("does not exist"));
    }

    @Test
    void execute_passesCleanWorkspaceWithNoTraversal() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        AgentExecutionContext context = buildContext(workspace);

        // Clean workspace should not throw SecurityException
        step.execute(context);

        // No path traversal violations — only check for security-related issues
        assertThat(context.getContractViolations())
                .noneMatch(v -> v.contains("FATAL"));
    }

    @Test
    void execute_detectsOversizedFile() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        // Create a file > 1MB
        byte[] bigContent = new byte[1_100_000];
        Files.write(workspace.resolve("backend/big-file.dat"), bigContent);
        AgentExecutionContext context = buildContext(workspace);

        step.execute(context);

        assertThat(context.getContractViolations())
                .anyMatch(v -> v.contains("oversized") && v.contains("big-file.dat"));
    }

    @Test
    void execute_detectsMissingMvnwForJavaBackend() throws Exception {
        ArtifactContractValidateStep step = new ArtifactContractValidateStep();
        Path workspace = createCompleteWorkspace();
        Files.delete(workspace.resolve("backend/mvnw"));
        Files.delete(workspace.resolve("backend/mvnw.cmd"));
        AgentExecutionContext context = buildContext(workspace);

        step.execute(context);

        assertThat(context.getContractViolations())
                .anyMatch(v -> v.contains("mvnw"));
    }

    private Path createCompleteWorkspace() throws Exception {
        Path workspace = tempDir.resolve("ws-" + System.nanoTime());
        Files.createDirectories(workspace.resolve("backend"));
        Files.createDirectories(workspace.resolve("frontend/src"));

        Files.writeString(workspace.resolve("README.md"), "# Project", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("docker-compose.yml"),
                "services:\n  backend:\n    build:\n      context: ./backend\n",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("backend/mvnw"), "#!/bin/sh\nmvn \"$@\"", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("backend/mvnw.cmd"), "@echo off\nmvn %*", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("backend/Dockerfile"), "FROM eclipse-temurin:17-jre", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("frontend/package.json"), "{\"name\":\"app\"}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("frontend/index.html"), "<!DOCTYPE html><html><body><div id=\"app\"></div></body></html>", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("frontend/vite.config.ts"), "import { defineConfig } from 'vite'\nexport default defineConfig({})", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("frontend/Dockerfile"), "FROM node:20-alpine", StandardCharsets.UTF_8);

        return workspace;
    }

    private AgentExecutionContext buildContext(Path workspace) {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-acv");
        task.setProjectId("project-acv");
        context.setTask(task);
        context.setWorkspaceDir(workspace);
        return context;
    }
}
