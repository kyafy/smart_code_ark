package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class QualityGateService {
    private static final Logger logger = LoggerFactory.getLogger(QualityGateService.class);

    private final ObjectMapper objectMapper;

    public QualityGateService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public QualityGateResult evaluate(Path workspaceDir) {
        if (workspaceDir == null || !Files.exists(workspaceDir)) {
            return new QualityGateResult(false, 0.0, List.of("quality_gate_workspace_missing"), LocalDateTime.now().toString());
        }
        List<String> failedRules = new ArrayList<>();
        int totalChecks = 3;
        int passedChecks = 0;

        if (checkStructureGate(workspaceDir, failedRules)) {
            passedChecks++;
        }
        if (checkSemanticGate(workspaceDir, failedRules)) {
            passedChecks++;
        }
        if (checkBuildGate(workspaceDir, failedRules)) {
            passedChecks++;
        }

        double score = totalChecks == 0 ? 0.0 : (double) passedChecks / (double) totalChecks;
        boolean passed = failedRules.isEmpty();
        return new QualityGateResult(passed, score, failedRules, LocalDateTime.now().toString());
    }

    public void persistReport(Path workspaceDir, QualityGateResult result) {
        if (workspaceDir == null || result == null) {
            return;
        }
        try {
            Path report = workspaceDir.resolve("quality_gate_report.json");
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            Files.writeString(report, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Persist quality gate report failed, error={}", e.getMessage());
        }
    }

    public List<String> autoFix(Path workspaceDir, List<String> failedRules) {
        List<String> fixedActions = new ArrayList<>();
        if (workspaceDir == null || !Files.exists(workspaceDir)) {
            return fixedActions;
        }
        if (failedRules != null && failedRules.contains("quality_gate_structure_path_traversal")) {
            logger.warn("Skip quality gate auto-fix because path traversal rule is triggered");
            return fixedActions;
        }
        try {
            String backendDir = detectBackendDir(workspaceDir);
            String frontendDir = detectFrontendDir(workspaceDir);
            ensureStartScript(workspaceDir, fixedActions);
            ensureDeployDoc(workspaceDir, fixedActions);
            ensureComposeAndRepairContext(workspaceDir, backendDir, frontendDir, fixedActions);
        } catch (Exception e) {
            logger.warn("Quality gate auto-fix failed, error={}", e.getMessage());
        }
        return fixedActions;
    }

    private boolean checkStructureGate(Path workspaceDir, List<String> failedRules) {
        boolean ok = true;
        if (!Files.exists(workspaceDir.resolve("docker-compose.yml"))) {
            failedRules.add("quality_gate_structure_missing_compose");
            ok = false;
        }
        if (!Files.exists(workspaceDir.resolve("scripts/start.sh"))) {
            failedRules.add("quality_gate_structure_missing_start_script");
            ok = false;
        }
        if (!Files.exists(workspaceDir.resolve("docs/deploy.md"))) {
            failedRules.add("quality_gate_structure_missing_deploy_doc");
            ok = false;
        }
        if (!checkPathSafety(workspaceDir)) {
            failedRules.add("quality_gate_structure_path_traversal");
            ok = false;
        }
        return ok;
    }

    private boolean checkSemanticGate(Path workspaceDir, List<String> failedRules) {
        boolean ok = true;
        Path startScript = workspaceDir.resolve("scripts/start.sh");
        try {
            String startContent = Files.exists(startScript) ? Files.readString(startScript, StandardCharsets.UTF_8) : "";
            if (!(startContent.contains("docker compose up --build -d") || startContent.contains("docker compose up -d"))) {
                failedRules.add("quality_gate_semantic_invalid_start_script");
                ok = false;
            }
        } catch (Exception e) {
            failedRules.add("quality_gate_semantic_invalid_start_script");
            ok = false;
        }

        Path deployDoc = workspaceDir.resolve("docs/deploy.md");
        try {
            String deployContent = Files.exists(deployDoc) ? Files.readString(deployDoc, StandardCharsets.UTF_8) : "";
            if (!deployContent.toLowerCase().contains("docker compose")) {
                failedRules.add("quality_gate_semantic_missing_deploy_instruction");
                ok = false;
            }
        } catch (Exception e) {
            failedRules.add("quality_gate_semantic_missing_deploy_instruction");
            ok = false;
        }
        return ok;
    }

    @SuppressWarnings("unchecked")
    private boolean checkBuildGate(Path workspaceDir, List<String> failedRules) {
        Path composeFile = workspaceDir.resolve("docker-compose.yml");
        if (!Files.exists(composeFile)) {
            failedRules.add("quality_gate_build_compose_not_found");
            return false;
        }
        try {
            String content = Files.readString(composeFile, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Object loaded = yaml.load(content);
            if (!(loaded instanceof Map<?, ?> rootMap)) {
                failedRules.add("quality_gate_build_compose_invalid");
                return false;
            }
            Object servicesObj = rootMap.get("services");
            if (!(servicesObj instanceof Map<?, ?> servicesMap)) {
                failedRules.add("quality_gate_build_compose_missing_services");
                return false;
            }

            boolean ok = true;
            for (Map.Entry<?, ?> serviceEntry : servicesMap.entrySet()) {
                Object serviceName = serviceEntry.getKey();
                Object serviceVal = serviceEntry.getValue();
                if (!(serviceVal instanceof Map<?, ?> serviceMap)) {
                    continue;
                }
                Object buildObj = serviceMap.get("build");
                String contextPath = null;
                if (buildObj instanceof String buildStr) {
                    contextPath = buildStr;
                } else if (buildObj instanceof Map<?, ?> buildMap) {
                    Object ctx = buildMap.get("context");
                    if (ctx instanceof String ctxStr) {
                        contextPath = ctxStr;
                    }
                }
                if (contextPath == null || contextPath.isBlank()) {
                    continue;
                }
                String normalized = contextPath.startsWith("./") ? contextPath.substring(2) : contextPath;
                Path target = workspaceDir.resolve(normalized).normalize();
                if (!Files.isDirectory(target)) {
                    failedRules.add("quality_gate_build_invalid_compose_context:" + serviceName);
                    ok = false;
                }
            }
            return ok;
        } catch (Exception e) {
            failedRules.add("quality_gate_build_compose_parse_error");
            return false;
        }
    }

    private boolean checkPathSafety(Path workspaceDir) {
        try {
            Files.walkFileTree(workspaceDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relative = workspaceDir.relativize(file).toString().replace('\\', '/');
                    if (relative.contains("..")) {
                        throw new IllegalStateException("path traversal");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureStartScript(Path workspaceDir, List<String> fixedActions) throws IOException {
        Path scriptPath = workspaceDir.resolve("scripts/start.sh");
        String expectedContent =
                "#!/usr/bin/env bash\n" +
                        "set -euo pipefail\n\n" +
                        "ROOT_DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"\n" +
                        "cd \"$ROOT_DIR\"\n" +
                        "docker compose up --build -d\n" +
                        "echo \"services started\"\n";
        if (!Files.exists(scriptPath)) {
            if (scriptPath.getParent() != null) {
                Files.createDirectories(scriptPath.getParent());
            }
            Files.writeString(scriptPath, expectedContent, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_scripts_start_sh");
            return;
        }
        String existing = Files.readString(scriptPath, StandardCharsets.UTF_8);
        if (!existing.contains("docker compose up --build -d") && !existing.contains("docker compose up -d")) {
            Files.writeString(scriptPath, expectedContent, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_scripts_start_sh");
        }
    }

    private void ensureDeployDoc(Path workspaceDir, List<String> fixedActions) throws IOException {
        Path deployDocPath = workspaceDir.resolve("docs/deploy.md");
        String expectedContent =
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
                        "```\n";
        if (!Files.exists(deployDocPath)) {
            if (deployDocPath.getParent() != null) {
                Files.createDirectories(deployDocPath.getParent());
            }
            Files.writeString(deployDocPath, expectedContent, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docs_deploy_md");
            return;
        }
        String existing = Files.readString(deployDocPath, StandardCharsets.UTF_8);
        if (!existing.toLowerCase(Locale.ROOT).contains("docker compose")) {
            Files.writeString(deployDocPath, expectedContent, StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docs_deploy_md");
        }
    }

    @SuppressWarnings("unchecked")
    private void ensureComposeAndRepairContext(Path workspaceDir,
                                               String backendDir,
                                               String frontendDir,
                                               List<String> fixedActions) throws IOException {
        Path composePath = workspaceDir.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            Files.writeString(composePath, defaultCompose(backendDir, frontendDir), StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docker_compose_yml");
            return;
        }
        String content = Files.readString(composePath, StandardCharsets.UTF_8);
        Yaml yaml = new Yaml();
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> rootMap)) {
            Files.writeString(composePath, defaultCompose(backendDir, frontendDir), StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docker_compose_yml");
            return;
        }
        Object servicesObj = rootMap.get("services");
        if (!(servicesObj instanceof Map<?, ?> rawServices)) {
            Files.writeString(composePath, defaultCompose(backendDir, frontendDir), StandardCharsets.UTF_8);
            addUnique(fixedActions, "generated_docker_compose_yml");
            return;
        }

        boolean changed = false;
        for (Map.Entry<?, ?> serviceEntry : rawServices.entrySet()) {
            String serviceName = String.valueOf(serviceEntry.getKey());
            Object serviceVal = serviceEntry.getValue();
            if (!(serviceVal instanceof Map<?, ?> serviceMapView)) {
                continue;
            }
            Map<Object, Object> serviceMap = (Map<Object, Object>) serviceMapView;
            Object buildObj = serviceMap.get("build");
            if (buildObj == null) {
                continue;
            }

            if (buildObj instanceof String buildStr) {
                if (isValidComposeContext(workspaceDir, buildStr)) {
                    continue;
                }
                String replacement = chooseComposeContext(serviceName, backendDir, frontendDir);
                Map<String, Object> newBuild = new LinkedHashMap<>();
                newBuild.put("context", "./" + replacement);
                serviceMap.put("build", newBuild);
                addUnique(fixedActions, "repaired_compose_context:" + normalizeComposeService(serviceName, replacement, backendDir, frontendDir));
                changed = true;
                continue;
            }

            if (buildObj instanceof Map<?, ?> buildMapView) {
                Map<Object, Object> buildMap = (Map<Object, Object>) buildMapView;
                Object contextObj = buildMap.get("context");
                if (contextObj instanceof String contextStr && isValidComposeContext(workspaceDir, contextStr)) {
                    continue;
                }
                String replacement = chooseComposeContext(serviceName, backendDir, frontendDir);
                buildMap.put("context", "./" + replacement);
                addUnique(fixedActions, "repaired_compose_context:" + normalizeComposeService(serviceName, replacement, backendDir, frontendDir));
                changed = true;
            }
        }

        if (changed) {
            Files.writeString(composePath, yaml.dump(rootMap), StandardCharsets.UTF_8);
        }
    }

    private String defaultCompose(String backendDir, String frontendDir) {
        return "services:\n" +
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
    }

    private boolean isValidComposeContext(Path workspaceDir, String contextPath) {
        if (contextPath == null || contextPath.isBlank()) {
            return false;
        }
        String normalized = contextPath.startsWith("./") ? contextPath.substring(2) : contextPath;
        if (normalized.isBlank()) {
            return false;
        }
        Path target = workspaceDir.resolve(normalized).normalize();
        return Files.isDirectory(target);
    }

    private String detectBackendDir(Path workspaceDir) {
        List<String> candidates = List.of("backend", "services/api-gateway-java", "api", "server");
        for (String candidate : candidates) {
            Path candidatePath = workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("pom.xml"))
                    || Files.exists(candidatePath.resolve("build.gradle"))
                    || Files.exists(candidatePath.resolve("package.json"))) {
                return candidate.replace("\\", "/").replaceAll("^\\./+", "");
            }
        }
        return "backend";
    }

    private String detectFrontendDir(Path workspaceDir) {
        List<String> candidates = List.of("frontend", "frontend-web", "web", "client", "app");
        for (String candidate : candidates) {
            Path candidatePath = workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("package.json"))
                    || Files.exists(candidatePath.resolve("vite.config.ts"))
                    || Files.exists(candidatePath.resolve("src"))) {
                return candidate.replace("\\", "/").replaceAll("^\\./+", "");
            }
        }
        return "frontend";
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

    private String normalizeComposeService(String serviceName,
                                           String replacement,
                                           String backendDir,
                                           String frontendDir) {
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

    private void addUnique(List<String> values, String value) {
        if (value == null || value.isBlank() || values.contains(value)) {
            return;
        }
        values.add(value);
    }

    public record QualityGateResult(
            boolean passed,
            double score,
            List<String> failedRules,
            String generatedAt
    ) {
    }
}
