package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.dto.ContractReportResult;
import com.smartark.gateway.dto.DeliveryManifestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String RULE_MISSING_REQUIRED_FILE = "missing_required_file";
    private static final String RULE_INVALID_COMPOSE_CONTEXT = "invalid_compose_context";
    private static final String RULE_INVALID_START_SCRIPT = "invalid_start_script";

    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;
    @Value("${smartark.delivery.guard.enabled:true}")
    private boolean deliveryGuardEnabled = true;

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
        if (deliveryGuardEnabled) {
            DeliveryContractOutcome outcome = enforceDeliveryContract(context);
            writeContractReport(context.getWorkspaceDir(), outcome);
            writeDeliveryManifest(context.getWorkspaceDir(), outcome);
        } else {
            logger.info("Delivery guard disabled, skip contract enforcement and manifest generation");
        }
        logger.info("Packaging artifacts...");
        String zipPath = packageArtifacts(context);
        saveArtifact(context.getTask(), zipPath);
    }

    private DeliveryContractOutcome enforceDeliveryContract(AgentExecutionContext context) throws IOException {
        Path workspaceDir = context.getWorkspaceDir();
        if (workspaceDir == null) {
            throw new IOException("Workspace directory is missing");
        }
        Files.createDirectories(workspaceDir);

        StackSpec stackSpec = resolveStackSpec(context);
        String backendDir = detectBackendDir(workspaceDir, stackSpec.backend());
        String frontendDir = detectFrontendDir(workspaceDir, stackSpec.frontend());
        List<String> failedRules = new ArrayList<>();
        List<String> fixedActions = new ArrayList<>();

        if (hasMissingRequiredFiles(workspaceDir, backendDir, frontendDir, stackSpec.backend())) {
            addUnique(failedRules, RULE_MISSING_REQUIRED_FILE);
        }
        if (isInvalidStartScript(workspaceDir)) {
            addUnique(failedRules, RULE_INVALID_START_SCRIPT);
        }

        ensureCommonDeliveryFiles(workspaceDir, fixedActions);
        ensureBackendDeliveryFiles(workspaceDir, backendDir, stackSpec.backend(), fixedActions);
        ensureFrontendDeliveryFiles(workspaceDir, frontendDir, stackSpec.frontend(), fixedActions);
        ComposeRepairResult composeRepairResult = ensureAndRepairDockerCompose(workspaceDir, backendDir, frontendDir);
        if (composeRepairResult.generatedCompose()) {
            addUnique(fixedActions, "generated_docker_compose_yml");
        }
        if (!composeRepairResult.repairedServices().isEmpty()) {
            addUnique(failedRules, RULE_INVALID_COMPOSE_CONTEXT);
            for (String service : composeRepairResult.repairedServices()) {
                addUnique(fixedActions, "repaired_compose_context:" + service);
            }
        }

        boolean passed = failedRules.isEmpty() || isContractValidAfterRepair(workspaceDir, backendDir, frontendDir, stackSpec.backend());
        return new DeliveryContractOutcome(stackSpec, backendDir, frontendDir, passed, failedRules, fixedActions);
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

    private void ensureCommonDeliveryFiles(Path workspaceDir, List<String> fixedActions) throws IOException {
        if (writeIfMissing(
                workspaceDir.resolve("scripts/start.sh"),
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose up --build -d\n" +
                        "echo \"services started\"\n"
        )) {
            addUnique(fixedActions, "generated_scripts_start_sh");
        }
        if (writeIfMissing(
                workspaceDir.resolve("scripts/deploy.sh"),
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose build --pull\n" +
                        "docker compose up -d\n"
        )) {
            addUnique(fixedActions, "generated_scripts_deploy_sh");
        }
        if (writeIfMissing(
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
        )) {
            addUnique(fixedActions, "generated_docs_deploy_md");
        }
    }

    private void ensureBackendDeliveryFiles(Path workspaceDir, String backendDir, String backendStack, List<String> fixedActions) throws IOException {
        String backend = backendStack == null ? "" : backendStack.toLowerCase(Locale.ROOT);
        Path backendPath = workspaceDir.resolve(backendDir);
        Files.createDirectories(backendPath);
        if (backend.contains("spring") || backend.contains("java")) {
            if (writeIfMissing(
                    backendPath.resolve("mvnw"),
                    "#!/usr/bin/env sh\n" +
                            "set -e\n" +
                            "exec mvn \"$@\"\n"
            )) {
                addUnique(fixedActions, "generated_backend_mvnw");
            }
            if (writeIfMissing(
                    backendPath.resolve("mvnw.cmd"),
                    "@echo off\r\n" +
                            "mvn %*\r\n"
            )) {
                addUnique(fixedActions, "generated_backend_mvnw_cmd");
            }
            if (writeIfMissing(
                    backendPath.resolve("pom.xml"),
                    "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n" +
                            "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                            "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n" +
                            "  <modelVersion>4.0.0</modelVersion>\n" +
                            "  <groupId>com.example</groupId>\n" +
                            "  <artifactId>app</artifactId>\n" +
                            "  <version>0.0.1-SNAPSHOT</version>\n" +
                            "</project>\n"
            )) {
                addUnique(fixedActions, "generated_backend_pom_xml");
            }
            if (writeIfMissing(
                    backendPath.resolve("Dockerfile"),
                    "FROM maven:3.9-eclipse-temurin-17 AS build\n" +
                            "WORKDIR /app\n" +
                            "COPY pom.xml .\n" +
                            "RUN mvn dependency:go-offline -B || true\n" +
                            "COPY src ./src\n" +
                            "RUN mvn package -DskipTests -B\n\n" +
                            "FROM eclipse-temurin:17-jre\n" +
                            "WORKDIR /app\n" +
                            "COPY --from=build /app/target/*.jar app.jar\n" +
                            "EXPOSE 8080\n" +
                            "ENTRYPOINT [\"java\", \"-jar\", \"app.jar\"]\n"
            )) {
                addUnique(fixedActions, "generated_backend_dockerfile");
            }
        } else {
            if (writeIfMissing(
                    backendPath.resolve("Dockerfile"),
                    "FROM node:20-alpine\n" +
                            "WORKDIR /app\n" +
                            "COPY package*.json ./\n" +
                            "RUN npm install\n" +
                            "COPY . .\n" +
                            "EXPOSE 3000\n" +
                            "CMD [\"npm\", \"start\"]\n"
            )) {
                addUnique(fixedActions, "generated_backend_dockerfile");
            }
        }
    }

    private void ensureFrontendDeliveryFiles(Path workspaceDir, String frontendDir, String frontendStack, List<String> fixedActions) throws IOException {
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
                    "    \"react-dom\": \"^18.3.1\",\n" +
                    "    \"react-router-dom\": \"^6.28.0\",\n" +
                    "    \"axios\": \"^1.7.7\"\n" +
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
                    "    \"vue\": \"^3.5.13\",\n" +
                    "    \"vue-router\": \"^4.4.5\",\n" +
                    "    \"pinia\": \"^2.2.4\",\n" +
                    "    \"axios\": \"^1.7.7\"\n" +
                    "  },\n" +
                    "  \"devDependencies\": {\n" +
                    "    \"vite\": \"^5.4.10\",\n" +
                    "    \"@vitejs/plugin-vue\": \"^5.1.4\"\n" +
                    "  }\n" +
                    "}\n";
            Files.writeString(frontendPath.resolve("package.json"), packageJson, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_frontend_package_json");
        }

        boolean isReactStack = frontendStack != null && frontendStack.toLowerCase(Locale.ROOT).contains("react");
        String mainEntry = isReactStack ? "/src/main.tsx" : "/src/main.ts";
        if (writeIfMissing(
                frontendPath.resolve("index.html"),
                "<!DOCTYPE html>\n" +
                        "<html lang=\"zh-CN\">\n" +
                        "<head>\n" +
                        "  <meta charset=\"UTF-8\" />\n" +
                        "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n" +
                        "  <title>App</title>\n" +
                        "</head>\n" +
                        "<body>\n" +
                        "  <div id=\"app\"></div>\n" +
                        "  <script type=\"module\" src=\"" + mainEntry + "\"></script>\n" +
                        "</body>\n" +
                        "</html>\n"
        )) {
            addUnique(fixedActions, "generated_frontend_index_html");
        }

        String vitePlugin = isReactStack
                ? "import react from '@vitejs/plugin-react'\n\nexport default defineConfig({\n  plugins: [react()],\n  server: { host: '0.0.0.0', port: 5173 }\n})\n"
                : "import vue from '@vitejs/plugin-vue'\n\nexport default defineConfig({\n  plugins: [vue()],\n  server: { host: '0.0.0.0', port: 5173 }\n})\n";
        if (writeIfMissing(
                frontendPath.resolve("vite.config.ts"),
                "import { defineConfig } from 'vite'\n" + vitePlugin
        )) {
            addUnique(fixedActions, "generated_frontend_vite_config");
        }

        if (writeIfMissing(
                frontendPath.resolve("Dockerfile"),
                "FROM node:20-alpine AS build\n" +
                        "WORKDIR /app\n" +
                        "COPY package*.json ./\n" +
                        "RUN npm install\n" +
                        "COPY . .\n" +
                        "RUN npm run build\n\n" +
                        "FROM nginx:alpine\n" +
                        "COPY --from=build /app/dist /usr/share/nginx/html\n" +
                        "EXPOSE 80\n" +
                        "CMD [\"nginx\", \"-g\", \"daemon off;\"]\n"
        )) {
            addUnique(fixedActions, "generated_frontend_dockerfile");
        }
    }

    private ComposeRepairResult ensureAndRepairDockerCompose(Path workspaceDir, String backendDir, String frontendDir) throws IOException {
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
            return new ComposeRepairResult(true, List.of());
        }

        List<String> lines = Files.readAllLines(composePath, StandardCharsets.UTF_8);
        List<String> fixed = new ArrayList<>();
        List<String> repairedServices = new ArrayList<>();
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
                if (!raw.isBlank() && Files.isDirectory(candidate)) {
                    fixed.add(line);
                    continue;
                }
                String replacement = chooseComposeContext(activeService, backendDir, frontendDir);
                int contextIndex = line.indexOf("context:");
                String indent = contextIndex < 0 ? "" : line.substring(0, contextIndex);
                fixed.add(indent + "context: ./" + replacement);
                addUnique(repairedServices, normalizeComposeServiceName(activeService, replacement, backendDir, frontendDir));
                continue;
            }
            fixed.add(line);
        }
        Files.write(composePath, fixed, StandardCharsets.UTF_8);
        return new ComposeRepairResult(false, repairedServices);
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

    private String normalizeComposeServiceName(String serviceName, String replacement, String backendDir, String frontendDir) {
        String service = serviceName == null ? "" : serviceName.toLowerCase(Locale.ROOT);
        if (service.contains("front") || service.contains("web") || service.contains("client")) {
            return "frontend";
        }
        if (service.contains("back") || service.contains("api") || service.contains("server")) {
            return "backend";
        }
        if (frontendDir.equals(replacement)) {
            return "frontend";
        }
        if (backendDir.equals(replacement)) {
            return "backend";
        }
        return service.isBlank() ? "backend" : service;
    }

    private boolean hasMissingRequiredFiles(Path workspaceDir, String backendDir, String frontendDir, String backendStack) {
        if (!Files.exists(workspaceDir.resolve("docker-compose.yml"))) {
            return true;
        }
        if (!Files.exists(workspaceDir.resolve("scripts/start.sh"))) {
            return true;
        }
        if (!Files.exists(workspaceDir.resolve(frontendDir).resolve("package.json"))) {
            return true;
        }
        String backend = backendStack == null ? "" : backendStack.toLowerCase(Locale.ROOT);
        if (backend.contains("spring") || backend.contains("java")) {
            Path backendPath = workspaceDir.resolve(backendDir);
            if (!Files.exists(backendPath.resolve("pom.xml"))
                    || !Files.exists(backendPath.resolve("mvnw"))
                    || !Files.exists(backendPath.resolve("mvnw.cmd"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isInvalidStartScript(Path workspaceDir) {
        Path startScript = workspaceDir.resolve("scripts/start.sh");
        if (!Files.exists(startScript)) {
            return true;
        }
        try {
            String content = Files.readString(startScript, StandardCharsets.UTF_8);
            return !content.contains("docker compose up --build -d") && !content.contains("docker compose up -d");
        } catch (IOException e) {
            return true;
        }
    }

    private boolean hasInvalidComposeContext(Path workspaceDir) {
        Path composePath = workspaceDir.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            return true;
        }
        try {
            List<String> lines = Files.readAllLines(composePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("context:")) {
                    continue;
                }
                String raw = trimmed.substring("context:".length()).trim().replace("\"", "").replace("'", "");
                if (raw.isBlank()) {
                    return true;
                }
                String normalized = raw.startsWith("./") ? raw.substring(2) : raw;
                Path contextPath = workspaceDir.resolve(normalized).normalize();
                if (!Files.isDirectory(contextPath)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private boolean isContractValidAfterRepair(Path workspaceDir, String backendDir, String frontendDir, String backendStack) {
        return !hasMissingRequiredFiles(workspaceDir, backendDir, frontendDir, backendStack)
                && !isInvalidStartScript(workspaceDir)
                && !hasInvalidComposeContext(workspaceDir);
    }

    private void writeContractReport(Path workspaceDir, DeliveryContractOutcome outcome) throws IOException {
        ContractReportResult report = new ContractReportResult(
                outcome.passed(),
                List.copyOf(outcome.failedRules()),
                List.copyOf(outcome.fixedActions()),
                LocalDateTime.now().toString()
        );
        writeJsonFile(workspaceDir.resolve("contract_report.json"), report);
    }

    private void writeDeliveryManifest(Path workspaceDir, DeliveryContractOutcome outcome) throws IOException {
        DeliveryManifestResult manifest = new DeliveryManifestResult(
                resolveStackName(outcome.stackSpec()),
                List.of(outcome.backendDir(), outcome.frontendDir()),
                List.of("backend", "frontend"),
                List.of(
                        "docker compose up --build -d",
                        "bash scripts/start.sh"
                )
        );
        writeJsonFile(workspaceDir.resolve("delivery_manifest.json"), manifest);
    }

    private String resolveStackName(StackSpec stackSpec) {
        String backend = normalizeStackPart(stackSpec.backend(), "springboot");
        String frontend = normalizeStackPart(stackSpec.frontend(), "vue3");
        return backend + "+" + frontend;
    }

    private String normalizeStackPart(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private void writeJsonFile(Path path, Object payload) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private boolean writeIfMissing(Path path, String content) throws IOException {
        if (Files.exists(path)) {
            return false;
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return true;
    }

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank() || values.contains(value)) {
            return;
        }
        values.add(value);
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
                    ZipEntry zipEntry = new ZipEntry(sourceDir.relativize(path).toString().replace('\\', '/'));
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

    private record ComposeRepairResult(boolean generatedCompose, List<String> repairedServices) {
    }

    private record DeliveryContractOutcome(
            StackSpec stackSpec,
            String backendDir,
            String frontendDir,
            boolean passed,
            List<String> failedRules,
            List<String> fixedActions
    ) {
    }

    private record StackSpec(String backend, String frontend) {
    }
}
