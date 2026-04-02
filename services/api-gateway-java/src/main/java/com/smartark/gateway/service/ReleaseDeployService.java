package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class ReleaseDeployService {
    public static final String IMAGE_BUILD_REPORT_FILE = "release_image_build_report.json";
    public static final String IMAGE_PUSH_REPORT_FILE = "release_image_push_report.json";
    public static final String DEPLOY_REPORT_FILE = "release_deploy_report.json";
    public static final String VERIFY_REPORT_FILE = "release_verify_report.json";
    public static final String ROLLBACK_REPORT_FILE = "release_rollback_report.json";

    private final ObjectMapper objectMapper;

    @Value("${smartark.release.enabled:true}")
    private boolean releaseEnabled;

    @Value("${smartark.release.command-execution-enabled:true}")
    private boolean commandExecutionEnabled;

    @Value("${smartark.release.timeout-seconds:900}")
    private long timeoutSeconds;

    @Value("${smartark.release.registry-prefix:}")
    private String registryPrefix;

    @Value("${smartark.release.verify-health-url:}")
    private String verifyHealthUrl;

    @Value("${smartark.release.k8s.namespace:}")
    private String releaseK8sNamespace;

    @Value("${smartark.release.k8s.rollback-enabled:true}")
    private boolean releaseK8sRollbackEnabled;

    @Value("${smartark.release.k8s.rollback-kinds:deployment,statefulset,daemonset}")
    private String releaseK8sRollbackKinds;

    public ReleaseDeployService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public boolean shouldRunReleasePipeline(TaskEntity task) {
        if (!releaseEnabled || task == null) {
            return false;
        }
        return isCompose(task.getDeployMode())
                || isK8s(task.getDeployMode())
                || Boolean.TRUE.equals(task.getAutoBuildImage())
                || Boolean.TRUE.equals(task.getAutoPushImage())
                || Boolean.TRUE.equals(task.getAutoDeployTarget());
    }

    public ReleaseReport buildImages(TaskEntity task, Path workspaceDir) throws IOException {
        if (!shouldRunReleasePipeline(task)) {
            return writeReport(workspaceDir, IMAGE_BUILD_REPORT_FILE, skippedReport("image_build", task, "release pipeline disabled"));
        }

        boolean buildRequested = Boolean.TRUE.equals(task.getAutoBuildImage())
                || Boolean.TRUE.equals(task.getAutoPushImage())
                || Boolean.TRUE.equals(task.getAutoDeployTarget())
                || isCompose(task.getDeployMode())
                || isK8s(task.getDeployMode());
        if (!buildRequested) {
            return writeReport(workspaceDir, IMAGE_BUILD_REPORT_FILE, skippedReport("image_build", task, "image build not requested"));
        }
        if (!commandExecutionEnabled) {
            return writeReport(workspaceDir, IMAGE_BUILD_REPORT_FILE, failedReport(
                    "image_build", task, List.of(), List.of(new IssueItem("release_command_execution_disabled",
                            "release command execution is disabled", null)),
                    List.of()
            ));
        }

        List<IssueItem> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<CommandResult> commands = new ArrayList<>();
        Map<String, String> imageRefs = resolveImageRefs(task);

        Path backendDir = detectBackendDir(workspaceDir);
        if (backendDir != null && Files.exists(backendDir.resolve("Dockerfile"))) {
            commands.add(executeCommand(
                    "image-build-backend",
                    workspaceDir,
                    backendDir,
                    detectDockerBuildCommand(imageRefs.get("backend"), "Dockerfile"),
                    issues
            ));
        } else {
            warnings.add("backend Dockerfile not found, skip backend image build");
        }

        Path frontendDir = detectFrontendDir(workspaceDir);
        if (frontendDir != null && Files.exists(frontendDir.resolve("Dockerfile"))) {
            commands.add(executeCommand(
                    "image-build-frontend",
                    workspaceDir,
                    frontendDir,
                    detectDockerBuildCommand(imageRefs.get("frontend"), "Dockerfile"),
                    issues
            ));
        } else {
            warnings.add("frontend Dockerfile not found, skip frontend image build");
        }

        if (commands.isEmpty()) {
            issues.add(new IssueItem("release_build_no_targets", "no docker build target found", null));
        }

        return writeReport(workspaceDir, IMAGE_BUILD_REPORT_FILE,
                buildReport("image_build", task, issues.isEmpty(), false, imageRefs, commands, issues, warnings));
    }

    public ReleaseReport pushImages(TaskEntity task, Path workspaceDir) throws IOException {
        if (!shouldRunReleasePipeline(task)) {
            return writeReport(workspaceDir, IMAGE_PUSH_REPORT_FILE, skippedReport("image_push", task, "release pipeline disabled"));
        }
        if (!Boolean.TRUE.equals(task.getAutoPushImage())) {
            return writeReport(workspaceDir, IMAGE_PUSH_REPORT_FILE, skippedReport("image_push", task, "image push not requested"));
        }
        if (!commandExecutionEnabled) {
            return writeReport(workspaceDir, IMAGE_PUSH_REPORT_FILE, failedReport(
                    "image_push", task, List.of(), List.of(new IssueItem("release_command_execution_disabled",
                            "release command execution is disabled", null)),
                    List.of()
            ));
        }

        Map<String, String> imageRefs = loadImageRefs(workspaceDir);
        List<IssueItem> issues = new ArrayList<>();
        List<CommandResult> commands = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (imageRefs.isEmpty()) {
            issues.add(new IssueItem("release_push_no_images", "no image refs found from image_build stage", IMAGE_BUILD_REPORT_FILE));
        } else {
            for (Map.Entry<String, String> entry : imageRefs.entrySet()) {
                commands.add(executeCommand(
                        "image-push-" + sanitizeName(entry.getKey()),
                        workspaceDir,
                        workspaceDir,
                        detectDockerPushCommand(entry.getValue()),
                        issues
                ));
            }
        }
        return writeReport(workspaceDir, IMAGE_PUSH_REPORT_FILE,
                buildReport("image_push", task, issues.isEmpty(), false, imageRefs, commands, issues, warnings));
    }

    public ReleaseReport deployTarget(TaskEntity task, Path workspaceDir) throws IOException {
        if (!shouldRunReleasePipeline(task)) {
            return writeReport(workspaceDir, DEPLOY_REPORT_FILE, skippedReport("deploy_target", task, "release pipeline disabled"));
        }
        if (!Boolean.TRUE.equals(task.getAutoDeployTarget())) {
            return writeReport(workspaceDir, DEPLOY_REPORT_FILE, skippedReport("deploy_target", task, "target deploy not requested"));
        }
        if (!commandExecutionEnabled) {
            return writeReport(workspaceDir, DEPLOY_REPORT_FILE, failedReport(
                    "deploy_target", task, List.of(), List.of(new IssueItem("release_command_execution_disabled",
                            "release command execution is disabled", null)),
                    List.of()
            ));
        }

        List<IssueItem> issues = new ArrayList<>();
        List<CommandResult> commands = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> imageRefs = loadImageRefs(workspaceDir);

        String mode = normalizeDeployMode(task.getDeployMode());
        if ("compose".equals(mode)) {
            Path composeFile = workspaceDir.resolve("docker-compose.yml");
            if (!Files.exists(composeFile)) {
                issues.add(new IssueItem("release_deploy_missing_compose", "docker-compose.yml not found", null));
            } else {
                commands.add(executeCommand(
                        "deploy-compose-up",
                        workspaceDir,
                        workspaceDir,
                        detectComposeUpCommand(),
                        issues
                ));
            }
        } else if ("k8s".equals(mode)) {
            Path k8sDir = workspaceDir.resolve("k8s");
            if (!Files.isDirectory(k8sDir)) {
                issues.add(new IssueItem("release_deploy_missing_k8s", "k8s directory not found", null));
            } else {
                commands.add(executeCommand(
                        "deploy-k8s-apply",
                        workspaceDir,
                        workspaceDir,
                        detectK8sApplyCommand(k8sDir),
                        issues
                ));
            }
        } else {
            warnings.add("deploy mode is none, skip deploy execution");
        }

        return writeReport(workspaceDir, DEPLOY_REPORT_FILE,
                buildReport("deploy_target", task, issues.isEmpty(), false, imageRefs, commands, issues, warnings));
    }

    public ReleaseReport verifyTarget(TaskEntity task, Path workspaceDir) throws IOException {
        if (!shouldRunReleasePipeline(task)) {
            return writeReport(workspaceDir, VERIFY_REPORT_FILE, skippedReport("deploy_verify", task, "release pipeline disabled"));
        }
        if (!Boolean.TRUE.equals(task.getAutoDeployTarget())) {
            return writeReport(workspaceDir, VERIFY_REPORT_FILE, skippedReport("deploy_verify", task, "target deploy not requested"));
        }
        if (!commandExecutionEnabled) {
            return writeReport(workspaceDir, VERIFY_REPORT_FILE, failedReport(
                    "deploy_verify", task, List.of(), List.of(new IssueItem("release_command_execution_disabled",
                            "release command execution is disabled", null)),
                    List.of()
            ));
        }

        List<IssueItem> issues = new ArrayList<>();
        List<CommandResult> commands = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> imageRefs = loadImageRefs(workspaceDir);

        String mode = normalizeDeployMode(task.getDeployMode());
        if ("compose".equals(mode)) {
            commands.add(executeCommand(
                    "deploy-compose-ps",
                    workspaceDir,
                    workspaceDir,
                    detectComposePsCommand(),
                    issues
            ));
        } else if ("k8s".equals(mode)) {
            commands.add(executeCommand(
                    "deploy-k8s-get-pods",
                    workspaceDir,
                    workspaceDir,
                    detectK8sGetPodsCommand(),
                    issues
            ));
        } else {
            warnings.add("deploy mode is none, skip verify command");
        }

        if (verifyHealthUrl != null && !verifyHealthUrl.isBlank()) {
            boolean healthOk = verifyHealthEndpoint(verifyHealthUrl.trim());
            if (!healthOk) {
                issues.add(new IssueItem(
                        "release_verify_health_failed",
                        "health check failed for " + verifyHealthUrl.trim(),
                        null
                ));
            } else {
                warnings.add("health check passed: " + verifyHealthUrl.trim());
            }
        }

        return writeReport(workspaceDir, VERIFY_REPORT_FILE,
                buildReport("deploy_verify", task, issues.isEmpty(), false, imageRefs, commands, issues, warnings));
    }

    public ReleaseReport rollbackIfNeeded(TaskEntity task, Path workspaceDir) throws IOException {
        if (!shouldRunReleasePipeline(task)) {
            return writeReport(workspaceDir, ROLLBACK_REPORT_FILE, skippedReport("deploy_rollback", task, "release pipeline disabled"));
        }
        if (!needsRollback(workspaceDir)) {
            return writeReport(workspaceDir, ROLLBACK_REPORT_FILE, skippedReport("deploy_rollback", task, "no failed deploy/verify report"));
        }
        if (!commandExecutionEnabled) {
            return writeReport(workspaceDir, ROLLBACK_REPORT_FILE, failedReport(
                    "deploy_rollback", task, List.of(), List.of(new IssueItem("release_command_execution_disabled",
                            "release command execution is disabled", null)),
                    List.of()
            ));
        }

        List<IssueItem> issues = new ArrayList<>();
        List<CommandResult> commands = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> imageRefs = loadImageRefs(workspaceDir);

        String mode = normalizeDeployMode(task.getDeployMode());
        if ("compose".equals(mode)) {
            commands.add(executeCommand(
                    "rollback-compose-down",
                    workspaceDir,
                    workspaceDir,
                    detectComposeDownCommand(),
                    issues
            ));
        } else if ("k8s".equals(mode)) {
            if (!releaseK8sRollbackEnabled) {
                warnings.add("k8s rollback is disabled by configuration");
            } else {
                Path k8sDir = workspaceDir.resolve("k8s");
                if (!Files.isDirectory(k8sDir)) {
                    issues.add(new IssueItem("release_rollback_missing_k8s", "k8s directory not found", null));
                } else {
                    List<String> targets = detectK8sRollbackTargets(k8sDir);
                    if (targets.isEmpty()) {
                        issues.add(new IssueItem("release_rollback_no_k8s_targets",
                                "no rollback workload found under k8s manifests", null));
                    } else {
                        for (String target : targets) {
                            commands.add(executeCommand(
                                    "rollback-k8s-undo-" + sanitizeName(target),
                                    workspaceDir,
                                    workspaceDir,
                                    detectK8sRollbackCommand(target),
                                    issues
                            ));
                        }
                    }
                }
            }
        } else {
            warnings.add("deploy mode is none, skip rollback command");
        }

        return writeReport(workspaceDir, ROLLBACK_REPORT_FILE,
                buildReport("deploy_rollback", task, issues.isEmpty(), false, imageRefs, commands, issues, warnings));
    }

    public boolean needsRollback(Path workspaceDir) {
        return hasFailedReport(workspaceDir.resolve(DEPLOY_REPORT_FILE))
                || hasFailedReport(workspaceDir.resolve(VERIFY_REPORT_FILE));
    }

    public boolean isStrict(TaskEntity task) {
        return task != null && Boolean.TRUE.equals(task.getStrictDelivery());
    }

    private boolean hasFailedReport(Path reportPath) {
        if (!Files.exists(reportPath)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(reportPath, StandardCharsets.UTF_8));
            boolean skipped = root.path("skipped").asBoolean(false);
            boolean passed = root.path("passed").asBoolean(true);
            return !skipped && !passed;
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> resolveImageRefs(TaskEntity task) {
        String projectCode = sanitizeName(task.getProjectId() == null ? "project" : task.getProjectId());
        String tag = sanitizeName(task.getId() == null ? "latest" : task.getId());
        if (tag.length() > 20) {
            tag = tag.substring(0, 20);
        }
        String prefix = registryPrefix == null ? "" : registryPrefix.trim();
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        if (prefix.isBlank()) {
            prefix = "smartark";
        }
        Map<String, String> imageRefs = new LinkedHashMap<>();
        imageRefs.put("backend", prefix + "/" + projectCode + "-backend:" + tag);
        imageRefs.put("frontend", prefix + "/" + projectCode + "-frontend:" + tag);
        return imageRefs;
    }

    private Map<String, String> loadImageRefs(Path workspaceDir) {
        Path buildReportPath = workspaceDir.resolve(IMAGE_BUILD_REPORT_FILE);
        if (!Files.exists(buildReportPath)) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(buildReportPath, StandardCharsets.UTF_8));
            JsonNode refs = root.path("imageRefs");
            if (!refs.isObject()) {
                return Map.of();
            }
            Map<String, String> imageRefs = new LinkedHashMap<>();
            refs.fields().forEachRemaining(entry -> {
                String value = entry.getValue() == null ? null : entry.getValue().asText(null);
                if (value != null && !value.isBlank()) {
                    imageRefs.put(entry.getKey(), value.trim());
                }
            });
            return imageRefs;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Path detectBackendDir(Path workspaceDir) {
        for (String candidate : List.of("backend", "services/api-gateway-java", "api", "server")) {
            Path dir = workspaceDir.resolve(candidate);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (Files.exists(dir.resolve("Dockerfile"))
                    || Files.exists(dir.resolve("pom.xml"))
                    || Files.exists(dir.resolve("build.gradle"))
                    || Files.exists(dir.resolve("build.gradle.kts"))
                    || Files.exists(dir.resolve("requirements.txt"))) {
                return dir;
            }
        }
        return null;
    }

    private Path detectFrontendDir(Path workspaceDir) {
        for (String candidate : List.of("frontend", "frontend-web", "frontend-mobile", "web", "client", "app", ".")) {
            Path dir = ".".equals(candidate) ? workspaceDir : workspaceDir.resolve(candidate);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            if (Files.exists(dir.resolve("Dockerfile"))
                    || Files.exists(dir.resolve("package.json"))
                    || Files.exists(dir.resolve("vite.config.ts"))
                    || Files.exists(dir.resolve("next.config.ts"))
                    || Files.isDirectory(dir.resolve("src"))) {
                return dir;
            }
        }
        return null;
    }

    private List<String> detectDockerBuildCommand(String imageRef, String dockerfileName) {
        if (isWindows()) {
            return List.of("cmd", "/c", "docker", "build", "-t", imageRef, "-f", dockerfileName, ".");
        }
        return List.of("docker", "build", "-t", imageRef, "-f", dockerfileName, ".");
    }

    private List<String> detectDockerPushCommand(String imageRef) {
        if (isWindows()) {
            return List.of("cmd", "/c", "docker", "push", imageRef);
        }
        return List.of("docker", "push", imageRef);
    }

    private List<String> detectComposeUpCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "docker", "compose", "up", "-d", "--build");
        }
        return List.of("docker", "compose", "up", "-d", "--build");
    }

    private List<String> detectComposePsCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "docker", "compose", "ps");
        }
        return List.of("docker", "compose", "ps");
    }

    private List<String> detectComposeDownCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "docker", "compose", "down");
        }
        return List.of("docker", "compose", "down");
    }

    private List<String> detectK8sApplyCommand(Path k8sDir) {
        if (isWindows()) {
            return List.of("cmd", "/c", "kubectl", "apply", "-f", k8sDir.toString());
        }
        return List.of("kubectl", "apply", "-f", k8sDir.toString());
    }

    private List<String> detectK8sGetPodsCommand() {
        if (isWindows()) {
            return List.of("cmd", "/c", "kubectl", "get", "pods");
        }
        return List.of("kubectl", "get", "pods");
    }

    private List<String> detectK8sRollbackCommand(String target) {
        String namespace = releaseK8sNamespace == null ? "" : releaseK8sNamespace.trim();
        List<String> parts = new ArrayList<>();
        if (isWindows()) {
            parts.add("cmd");
            parts.add("/c");
        }
        parts.add("kubectl");
        parts.add("rollout");
        parts.add("undo");
        parts.add(target);
        if (!namespace.isBlank()) {
            parts.add("-n");
            parts.add(namespace);
        }
        return List.copyOf(parts);
    }

    List<String> detectK8sRollbackTargets(Path k8sDir) throws IOException {
        if (k8sDir == null || !Files.isDirectory(k8sDir)) {
            return List.of();
        }
        Set<String> kinds = parseRollbackKinds();
        Set<String> targets = new LinkedHashSet<>();
        try (var stream = Files.walk(k8sDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .forEach(path -> targets.addAll(extractRollbackTargetsFromManifest(path, kinds)));
        }
        return List.copyOf(targets);
    }

    private List<String> extractRollbackTargetsFromManifest(Path file, Set<String> kinds) {
        List<String> targets = new ArrayList<>();
        try {
            String kind = null;
            String name = null;
            for (String rawLine : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if ("---".equals(line)) {
                    appendRollbackTarget(targets, kinds, kind, name);
                    kind = null;
                    name = null;
                    continue;
                }
                if (line.startsWith("kind:")) {
                    kind = parseYamlValue(line);
                    continue;
                }
                if (line.startsWith("name:") && name == null) {
                    name = parseYamlValue(line);
                }
            }
            appendRollbackTarget(targets, kinds, kind, name);
        } catch (Exception ignored) {
            return List.of();
        }
        return targets;
    }

    private void appendRollbackTarget(List<String> targets, Set<String> kinds, String kind, String name) {
        if (kind == null || kind.isBlank() || name == null || name.isBlank()) {
            return;
        }
        String resourceKind = normalizeK8sKind(kind);
        if (!kinds.contains(resourceKind)) {
            return;
        }
        targets.add(resourceKind + "/" + name.trim());
    }

    private String parseYamlValue(String line) {
        if (line == null) {
            return "";
        }
        int index = line.indexOf(':');
        if (index < 0 || index == line.length() - 1) {
            return "";
        }
        String value = line.substring(index + 1).trim();
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private Set<String> parseRollbackKinds() {
        String raw = releaseK8sRollbackKinds == null ? "" : releaseK8sRollbackKinds;
        Set<String> kinds = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String kind = normalizeK8sKind(item);
            if (!kind.isBlank()) {
                kinds.add(kind);
            }
        }
        if (kinds.isEmpty()) {
            kinds.add("deployment");
            kinds.add("statefulset");
            kinds.add("daemonset");
        }
        return kinds;
    }

    private String normalizeK8sKind(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("s")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private CommandResult executeCommand(
            String name,
            Path workspaceDir,
            Path workdir,
            List<String> command,
            List<IssueItem> issues
    ) throws IOException {
        Path logDir = workspaceDir.resolve(".smartark").resolve("release");
        Files.createDirectories(logDir);
        Path logPath = logDir.resolve(sanitizeName(name) + ".log");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(logPath.toFile());

        long startedAt = System.currentTimeMillis();
        Integer exitCode = null;
        String status = "passed";
        try {
            Process process = processBuilder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                status = "timeout";
                issues.add(new IssueItem(
                        "release_command_timeout",
                        name + " timed out after " + timeoutSeconds + "s",
                        relativePath(workspaceDir, logPath)
                ));
            } else {
                exitCode = process.exitValue();
                if (exitCode != 0) {
                    status = "failed";
                    issues.add(new IssueItem(
                            "release_command_failed",
                            name + " exited with code " + exitCode,
                            relativePath(workspaceDir, logPath)
                    ));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "interrupted";
            issues.add(new IssueItem(
                    "release_command_interrupted",
                    name + " interrupted while running",
                    relativePath(workspaceDir, logPath)
            ));
        } catch (IOException e) {
            status = "start_failed";
            issues.add(new IssueItem(
                    "release_command_start_failed",
                    name + " failed to start: " + e.getMessage(),
                    relativePath(workspaceDir, logPath)
            ));
        }

        return new CommandResult(
                name,
                String.join(" ", command),
                relativePath(workspaceDir, workdir),
                exitCode,
                Math.max(0L, System.currentTimeMillis() - startedAt),
                status,
                relativePath(workspaceDir, logPath)
        );
    }

    private boolean verifyHealthEndpoint(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private ReleaseReport skippedReport(String stage, TaskEntity task, String reason) {
        return new ReleaseReport(
                true,
                true,
                stage,
                normalizeDeployMode(task == null ? null : task.getDeployMode()),
                normalizeDeployEnv(task == null ? null : task.getDeployEnv()),
                Map.of(),
                List.of(),
                List.of(),
                List.of(reason),
                LocalDateTime.now().toString()
        );
    }

    private ReleaseReport failedReport(
            String stage,
            TaskEntity task,
            List<CommandResult> commands,
            List<IssueItem> issues,
            List<String> warnings
    ) {
        return new ReleaseReport(
                false,
                false,
                stage,
                normalizeDeployMode(task == null ? null : task.getDeployMode()),
                normalizeDeployEnv(task == null ? null : task.getDeployEnv()),
                Map.of(),
                commands,
                issues,
                warnings,
                LocalDateTime.now().toString()
        );
    }

    private ReleaseReport buildReport(
            String stage,
            TaskEntity task,
            boolean passed,
            boolean skipped,
            Map<String, String> imageRefs,
            List<CommandResult> commands,
            List<IssueItem> issues,
            List<String> warnings
    ) {
        return new ReleaseReport(
                passed,
                skipped,
                stage,
                normalizeDeployMode(task == null ? null : task.getDeployMode()),
                normalizeDeployEnv(task == null ? null : task.getDeployEnv()),
                imageRefs == null ? Map.of() : Map.copyOf(imageRefs),
                List.copyOf(commands),
                List.copyOf(issues),
                List.copyOf(warnings),
                LocalDateTime.now().toString()
        );
    }

    private ReleaseReport writeReport(Path workspaceDir, String fileName, ReleaseReport report) throws IOException {
        Path reportPath = workspaceDir.resolve(fileName);
        if (reportPath.getParent() != null) {
            Files.createDirectories(reportPath.getParent());
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
        Files.writeString(reportPath, json, StandardCharsets.UTF_8);
        return report;
    }

    private String relativePath(Path workspaceDir, Path path) {
        String relative = workspaceDir.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private String normalizeDeployMode(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("compose".equals(normalized) || "k8s".equals(normalized)) {
            return normalized;
        }
        return "none";
    }

    private String normalizeDeployEnv(String value) {
        if (value == null || value.isBlank()) {
            return "local";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local", "test", "staging", "prod" -> normalized;
            default -> "local";
        };
    }

    private boolean isCompose(String value) {
        return "compose".equals(normalizeDeployMode(value));
    }

    private boolean isK8s(String value) {
        return "k8s".equals(normalizeDeployMode(value));
    }

    private String sanitizeName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record CommandResult(
            String name,
            String command,
            String workdir,
            Integer exitCode,
            Long durationMs,
            String status,
            String logRef
    ) {
    }

    public record IssueItem(
            String code,
            String message,
            String logRef
    ) {
    }

    public record ReleaseReport(
            boolean passed,
            boolean skipped,
            String stage,
            String deployMode,
            String deployEnv,
            Map<String, String> imageRefs,
            List<CommandResult> commands,
            List<IssueItem> issues,
            List<String> warnings,
            String generatedAt
    ) {
    }
}
