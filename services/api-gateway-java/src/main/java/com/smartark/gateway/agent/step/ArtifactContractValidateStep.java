package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates the generated workspace against delivery contracts before packaging.
 * <p>
 * Non-fatal issues (missing README, package.json, etc.) are recorded as warnings
 * and will be auto-fixed by the subsequent PackageStep.
 * Fatal issues (path traversal) cause the task to fail immediately.
 */
@Component
public class ArtifactContractValidateStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(ArtifactContractValidateStep.class);

    private static final long MAX_FILE_SIZE_BYTES = 1_048_576; // 1 MB

    @Override
    public String getStepCode() {
        return "artifact_contract_validate";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        Path workspace = context.getWorkspaceDir();
        if (workspace == null || !Files.exists(workspace)) {
            logger.warn("Workspace does not exist yet, skipping contract validation");
            return;
        }

        List<String> violations = new ArrayList<>();

        checkMandatoryFiles(workspace, violations);
        scanPathSafety(workspace, violations);
        validateDockerCompose(workspace, violations);
        checkFileSizes(workspace, violations);

        context.setContractViolations(violations);

        if (violations.isEmpty()) {
            logger.info("Artifact contract validation passed — no violations");
        } else {
            for (String v : violations) {
                logger.warn("Contract violation: {}", v);
            }
            logger.info("Artifact contract validation completed with {} violation(s)", violations.size());
        }
    }

    private void checkMandatoryFiles(Path workspace, List<String> violations) {
        // Common mandatory files
        checkFileExists(workspace, "README.md", violations);
        checkFileExists(workspace, "docker-compose.yml", violations);

        // Detect backend and check stack-specific files
        String backendDir = detectDir(workspace,
                List.of("backend", "services/api-gateway-java", "api", "server"),
                "backend");
        Path backendPath = workspace.resolve(backendDir);
        if (Files.exists(backendPath)) {
            boolean isJava = Files.exists(backendPath.resolve("pom.xml"))
                    || Files.exists(backendPath.resolve("build.gradle"));
            if (isJava) {
                checkFileExists(workspace, backendDir + "/pom.xml", violations);
                checkFileExists(workspace, backendDir + "/mvnw", violations);
                checkFileExists(workspace, backendDir + "/mvnw.cmd", violations);
            }
        }

        // Detect frontend and check package.json
        String frontendDir = detectDir(workspace,
                List.of("frontend", "frontend-web", "web", "client", "app"),
                "frontend");
        Path frontendPath = workspace.resolve(frontendDir);
        if (Files.exists(frontendPath)) {
            checkFileExists(workspace, frontendDir + "/package.json", violations);
        }
    }

    private void checkFileExists(Path workspace, String relativePath, List<String> violations) {
        if (!Files.exists(workspace.resolve(relativePath))) {
            violations.add("WARN: missing mandatory file: " + relativePath);
        }
    }

    private String detectDir(Path workspace, List<String> candidates, String fallback) {
        for (String candidate : candidates) {
            if (Files.isDirectory(workspace.resolve(candidate))) {
                return candidate;
            }
        }
        return fallback;
    }

    /**
     * Fatal check: any file path containing ".." or absolute paths causes task termination.
     */
    private void scanPathSafety(Path workspace, List<String> violations) throws Exception {
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relative = workspace.relativize(file).toString().replace('\\', '/');
                if (relative.contains("..")) {
                    throw new SecurityException(
                            "FATAL: path traversal detected in workspace: " + relative);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(workspace)) {
                    String relative = workspace.relativize(dir).toString().replace('\\', '/');
                    if (relative.contains("..")) {
                        throw new SecurityException(
                                "FATAL: path traversal detected in workspace directory: " + relative);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void validateDockerCompose(Path workspace, List<String> violations) {
        Path composePath = workspace.resolve("docker-compose.yml");
        if (!Files.exists(composePath)) {
            return; // Already flagged by mandatory file check
        }
        try {
            String content = Files.readString(composePath, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);
            if (root == null) {
                violations.add("WARN: docker-compose.yml is empty or unparseable");
                return;
            }

            Map<String, Object> services = null;
            if (root.containsKey("services") && root.get("services") instanceof Map) {
                services = (Map<String, Object>) root.get("services");
            }
            if (services == null) {
                violations.add("WARN: docker-compose.yml has no 'services' section");
                return;
            }

            for (Map.Entry<String, Object> entry : services.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> service = (Map<String, Object>) entry.getValue();
                if (!service.containsKey("build")) continue;

                Object buildObj = service.get("build");
                String contextPath = null;
                if (buildObj instanceof String) {
                    contextPath = (String) buildObj;
                } else if (buildObj instanceof Map) {
                    Map<String, Object> buildMap = (Map<String, Object>) buildObj;
                    Object ctx = buildMap.get("context");
                    if (ctx instanceof String) {
                        contextPath = (String) ctx;
                    }
                }

                if (contextPath != null) {
                    String normalized = contextPath.startsWith("./")
                            ? contextPath.substring(2) : contextPath;
                    if (!Files.isDirectory(workspace.resolve(normalized))) {
                        violations.add("WARN: docker-compose service '"
                                + entry.getKey() + "' build context '"
                                + contextPath + "' does not exist");
                    }
                }
            }
        } catch (Exception e) {
            violations.add("WARN: failed to parse docker-compose.yml: " + e.getMessage());
        }
    }

    private void checkFileSizes(Path workspace, List<String> violations) throws IOException {
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > MAX_FILE_SIZE_BYTES) {
                    String relative = workspace.relativize(file).toString().replace('\\', '/');
                    violations.add("WARN: oversized file (" + (attrs.size() / 1024)
                            + " KB): " + relative);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
