package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class PackageStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(PackageStep.class);

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public PackageStep(ArtifactRepository artifactRepository, ObjectMapper objectMapper) {
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "package";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        enforceDeliveryContract(context);
        logger.info("Packaging artifacts...");
        String zipPath = packageArtifacts(context);
        saveArtifact(context.getTask(), zipPath);
    }

    private void enforceDeliveryContract(AgentExecutionContext context) throws IOException {
        Path workspaceDir = context.getWorkspaceDir();
        if (workspaceDir == null) {
            throw new IOException("Workspace directory is missing");
        }
        Files.createDirectories(workspaceDir);

        StackSpec stackSpec = resolveStackSpec(context);
        String backendDir = detectBackendDir(workspaceDir, stackSpec.backend());
        String frontendDir = detectFrontendDir(workspaceDir, stackSpec.frontend());

        ensureCommonDeliveryFiles(workspaceDir);
        ensureBackendDeliveryFiles(workspaceDir, backendDir, stackSpec.backend());
        ensureFrontendDeliveryFiles(workspaceDir, frontendDir, stackSpec.frontend());
        ensureAndRepairDockerCompose(workspaceDir, backendDir, frontendDir);
    }

    private StackSpec resolveStackSpec(AgentExecutionContext context) {
        if (context.getSpec() == null || context.getSpec().getRequirementJson() == null) {
            return new StackSpec("springboot", "vue3");
        }
        try {
            JsonNode root = objectMapper.readTree(context.getSpec().getRequirementJson());
            String backend = root.at("/stack/backend").asText("springboot");
            String frontend = root.at("/stack/frontend").asText("vue3");
            return new StackSpec(backend, frontend);
        } catch (Exception e) {
            logger.warn("Failed to parse stack from requirement json, using defaults", e);
            return new StackSpec("springboot", "vue3");
        }
    }

    private String detectBackendDir(Path workspaceDir, String backendStack) {
        List<String> candidates = List.of("backend", "services/api-gateway-java", "api", "server");
        for (String candidate : candidates) {
            Path candidatePath = workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("pom.xml")) || Files.exists(candidatePath.resolve("build.gradle"))
                    || Files.exists(candidatePath.resolve("package.json"))) {
                return normalizeRelative(candidate);
            }
        }
        if (backendStack != null && backendStack.toLowerCase(Locale.ROOT).contains("node")) {
            return "backend";
        }
        return "backend";
    }

    private String detectFrontendDir(Path workspaceDir, String frontendStack) {
        List<String> candidates = List.of("frontend", "frontend-web", "web", "client", "app");
        for (String candidate : candidates) {
            Path candidatePath = workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("package.json"))
                    || Files.exists(candidatePath.resolve("vite.config.ts"))
                    || Files.exists(candidatePath.resolve("src"))) {
                return normalizeRelative(candidate);
            }
        }
        if (frontendStack != null && frontendStack.toLowerCase(Locale.ROOT).contains("uni-app")) {
            return "frontend";
        }
        return "frontend";
    }

    private void ensureCommonDeliveryFiles(Path workspaceDir) throws IOException {
        writeIfMissing(
                workspaceDir.resolve("scripts/start.sh"),
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose up --build -d\n" +
                        "echo \"services started\"\n"
        );
        writeIfMissing(
                workspaceDir.resolve("scripts/deploy.sh"),
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose build --pull\n" +
                        "docker compose up -d\n"
        );
        writeIfMissing(
                workspaceDir.resolve("docs/deploy.md"),
                "# Deployment Guide\n\n" +
                        "## Prerequisites\n" +
                        "- Docker 24+\n" +
                        "- Docker Compose v2+\n\n" +
                        "## Quick Start\n" +
                        "```bash\n" +
                        "bash scripts/start.sh\n" +
                        "```\n\n" +
                        "## Rebuild\n" +
                        "```bash\n" +
                        "bash scripts/deploy.sh\n" +
                        "```\n"
        );
    }

    private void ensureBackendDeliveryFiles(Path workspaceDir, String backendDir, String backendStack) throws IOException {
        String backend = backendStack == null ? "" : backendStack.toLowerCase(Locale.ROOT);
        Path backendPath = workspaceDir.resolve(backendDir);
        Files.createDirectories(backendPath);
        if (backend.contains("spring") || backend.contains("java")) {
            writeIfMissing(
                    backendPath.resolve("mvnw"),
                    "#!/usr/bin/env sh\n" +
                            "set -e\n" +
                            "exec mvn \"$@\"\n"
            );
            writeIfMissing(
                    backendPath.resolve("mvnw.cmd"),
                    "@echo off\r\n" +
                            "mvn %*\r\n"
            );
            writeIfMissing(
                    backendPath.resolve("pom.xml"),
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                            "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                            "  <modelVersion>4.0.0</modelVersion>\n" +
                            "  <groupId>com.example</groupId>\n" +
                            "  <artifactId>app</artifactId>\n" +
                            "  <version>0.0.1-SNAPSHOT</version>\n" +
                            "</project>\n"
            );
        }
    }

    private void ensureFrontendDeliveryFiles(Path workspaceDir, String frontendDir, String frontendStack) throws IOException {
        Path frontendPath = workspaceDir.resolve(frontendDir);
        Files.createDirectories(frontendPath);
        if (!Files.exists(frontendPath.resolve("package.json"))) {
            boolean isReact = frontendStack != null && frontendStack.toLowerCase(Locale.ROOT).contains("react");
            String appName = frontendDir.replace("/", "-");
            String packageJson = isReact
                    ? "{\n" +
                    "  \"name\": \"" + appName + "\",\n" +
                    "  \"private\": true,\n" +
                    "  \"version\": \"0.0.1\",\n" +
                    "  \"scripts\": {\n" +
                    "    \"dev\": \"vite\",\n" +
                    "    \"build\": \"vite build\",\n" +
                    "    \"preview\": \"vite preview\"\n" +
                    "  },\n" +
                    "  \"dependencies\": {\n" +
                    "    \"react\": \"^18.3.1\",\n" +
                    "    \"react-dom\": \"^18.3.1\"\n" +
                    "  },\n" +
                    "  \"devDependencies\": {\n" +
                    "    \"vite\": \"^5.4.10\",\n" +
                    "    \"@vitejs/plugin-react\": \"^4.3.3\"\n" +
                    "  }\n" +
                    "}\n"
                    : "{\n" +
                    "  \"name\": \"" + appName + "\",\n" +
                    "  \"private\": true,\n" +
                    "  \"version\": \"0.0.1\",\n" +
                    "  \"scripts\": {\n" +
                    "    \"dev\": \"vite\",\n" +
                    "    \"build\": \"vite build\",\n" +
                    "    \"preview\": \"vite preview\"\n" +
                    "  },\n" +
                    "  \"dependencies\": {\n" +
                    "    \"vue\": \"^3.5.13\"\n" +
                    "  },\n" +
                    "  \"devDependencies\": {\n" +
                    "    \"vite\": \"^5.4.10\",\n" +
                    "    \"@vitejs/plugin-vue\": \"^5.1.4\"\n" +
                    "  }\n" +
                    "}\n";
            Files.writeString(frontendPath.resolve("package.json"), packageJson, StandardCharsets.UTF_8);
        }
    }

    private void ensureAndRepairDockerCompose(Path workspaceDir, String backendDir, String frontendDir) throws IOException {
        Path composePath = workspaceDir.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            String content =
                    "services:\n" +
                            "  backend:\n" +
                            "    build:\n" +
                            "      context: ./" + backendDir + "\n" +
                            "    ports:\n" +
                            "      - \"8080:8080\"\n" +
                            "  frontend:\n" +
                            "    build:\n" +
                            "      context: ./" + frontendDir + "\n" +
                            "    ports:\n" +
                            "      - \"5173:5173\"\n";
            Files.writeString(composePath, content, StandardCharsets.UTF_8);
            return;
        }

        List<String> lines = Files.readAllLines(composePath, StandardCharsets.UTF_8);
        List<String> fixed = new ArrayList<>();
        String activeService = "";
        for (String line : lines) {
            String trimmed = line.trim();
            if (line.startsWith("  ") && !line.startsWith("    ") && trimmed.endsWith(":")) {
                activeService = trimmed.substring(0, trimmed.length() - 1).toLowerCase(Locale.ROOT);
            }

            if (trimmed.startsWith("context:")) {
                String raw = trimmed.substring("context:".length()).trim().replace("\"", "").replace("'", "");
                String normalized = raw.startsWith("./") ? raw.substring(2) : raw;
                Path candidate = workspaceDir.resolve(normalized).normalize();
                if (!raw.isBlank() && Files.exists(candidate)) {
                    fixed.add(line);
                    continue;
                }
                String replacement = chooseComposeContext(activeService, backendDir, frontendDir);
                String indent = line.substring(0, line.indexOf('c'));
                fixed.add(indent + "context: ./" + replacement);
                continue;
            }
            fixed.add(line);
        }
        Files.write(composePath, fixed, StandardCharsets.UTF_8);
    }

    private String chooseComposeContext(String serviceName, String backendDir, String frontendDir) {
        String service = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
        if (service.contains("front") || service.contains("web") || service.contains("client")) {
            return frontendDir;
        }
        if (service.contains("back") || service.contains("api") || service.contains("server")) {
            return backendDir;
        }
        return backendDir;
    }

    private void writeIfMissing(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            return;
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String normalizeRelative(String value) {
        return value.replace("\\", "/").replaceAll("^\\./+", "");
    }

    private String packageArtifacts(AgentExecutionContext context) throws IOException {
        Path sourceDir = context.getWorkspaceDir();
        Path zipPath = sourceDir.getParent().resolve(context.getTask().getId() + ".zip");
        
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            Files.walk(sourceDir)
                .filter(path -> !Files.isDirectory(path))
                .forEach(path -> {
                    ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString());
                    try {
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        logger.error("Error adding file to zip", e);
                    }
                });
        }
        return "file://" + zipPath.toString();
    }

    private void saveArtifact(TaskEntity task, String storageUrl) {
        ArtifactEntity artifact = new ArtifactEntity();
        artifact.setTaskId(task.getId());
        artifact.setProjectId(task.getProjectId());
        artifact.setArtifactType("zip");
        artifact.setStorageUrl(storageUrl);
        artifact.setSizeBytes(new File(storageUrl.substring(7)).length());
        artifact.setCreatedAt(LocalDateTime.now());
        artifactRepository.save(artifact);
    }

    private record StackSpec(String backend, String frontend) {
    }
}
