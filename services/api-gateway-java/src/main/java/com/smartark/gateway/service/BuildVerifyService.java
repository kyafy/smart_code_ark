package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.dto.GenerateOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BuildVerifyService {
    private static final String BUILD_VERIFY_REPORT_FILE = "build_verify_report.json";
    private static final String RUNTIME_SMOKE_TEST_REPORT_FILE = "runtime_smoke_test_report.json";
    private static final Pattern NPM_RUN_SCRIPT_PATTERN = Pattern.compile("npm\\s+run\\s+([\\w:-]+)");

    private final ObjectMapper objectMapper;
    private final TemplateRepoService templateRepoService;

    @Value("${smartark.build-verify.enabled:true}")
    private boolean buildVerifyEnabled;

    @Value("${smartark.build-verify.command-execution-enabled:true}")
    private boolean commandExecutionEnabled;

    @Value("${smartark.build-verify.compose-check-enabled:true}")
    private boolean composeCheckEnabled;

    @Value("${smartark.build-verify.timeout-seconds:600}")
    private long timeoutSeconds;

    public BuildVerifyService(ObjectMapper objectMapper, TemplateRepoService templateRepoService) {
        this.objectMapper = objectMapper;
        this.templateRepoService = templateRepoService;
    }

    public BuildVerifyBundle verify(TaskEntity task, Path workspaceDir) throws IOException {
        Files.createDirectories(workspaceDir);

        String requestedLevel = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());
        List<BuildVerifyReportResult.CommandResult> commands = new ArrayList<>();
        List<BuildVerifyReportResult.IssueItem> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean skipped = false;

        if ("draft".equals(requestedLevel)) {
            skipped = true;
            warnings.add("build verify skipped for draft delivery level");
        } else if (!buildVerifyEnabled) {
            issues.add(new BuildVerifyReportResult.IssueItem(
                    "build_verify_disabled",
                    "build verify is disabled by configuration",
                    null
            ));
        } else {
            List<CommandSpec> commandSpecs = detectCommands(task, workspaceDir);
            if (commandSpecs.isEmpty()) {
                issues.add(new BuildVerifyReportResult.IssueItem(
                        "build_verify_no_targets",
                        "no build targets found under workspace",
                        null
                ));
            } else if (!commandExecutionEnabled) {
                issues.add(new BuildVerifyReportResult.IssueItem(
                        "build_verify_command_execution_disabled",
                        "build verify command execution is disabled by configuration",
                        null
                ));
                commands.addAll(commandSpecs.stream()
                        .map(commandSpec -> new BuildVerifyReportResult.CommandResult(
                                commandSpec.name(),
                                String.join(" ", commandSpec.command()),
                                relativePath(workspaceDir, commandSpec.workdir()),
                                null,
                                0L,
                                "not_executed",
                                null
                        ))
                        .toList());
            } else {
                for (CommandSpec commandSpec : commandSpecs) {
                    commands.add(executeCommand(commandSpec, workspaceDir, issues));
                }
            }
        }

        BuildVerifyReportResult buildReport = new BuildVerifyReportResult(
                issues.isEmpty(),
                skipped,
                requestedLevel,
                List.copyOf(commands),
                List.copyOf(issues),
                List.copyOf(warnings),
                LocalDateTime.now().toString()
        );
        DeliveryReportResult deliveryReport = buildDeliveryReport(task, buildReport);

        writeJsonFile(workspaceDir.resolve(BUILD_VERIFY_REPORT_FILE), buildReport);
        writeJsonFile(workspaceDir.resolve("delivery_report.json"), deliveryReport);
        return new BuildVerifyBundle(buildReport, deliveryReport);
    }

    private DeliveryReportResult buildDeliveryReport(TaskEntity task, BuildVerifyReportResult buildReport) {
        String requestedLevel = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());
        List<DeliveryReportResult.IssueItem> blockingIssues = buildReport.blockingIssues().stream()
                .map(issue -> new DeliveryReportResult.IssueItem(
                        "build_verify",
                        issue.code(),
                        issue.message(),
                        issue.logRef()
                ))
                .toList();
        List<String> warnings = new ArrayList<>(buildReport.warnings());

        String actualLevel;
        String status;
        boolean passed;

        if ("draft".equals(requestedLevel)) {
            actualLevel = "draft";
            status = "passed";
            passed = true;
        } else if ("validated".equals(requestedLevel)) {
            if (buildReport.passed()) {
                actualLevel = "validated";
                status = "passed";
                passed = true;
            } else {
                actualLevel = "draft";
                status = "failed";
                passed = false;
            }
        } else {
            if (buildReport.passed()) {
                actualLevel = "validated";
                status = "pending";
                passed = false;
                warnings.add("build verify passed, awaiting runtime_smoke_test before deliverable can pass");
            } else {
                actualLevel = "draft";
                status = "failed";
                passed = false;
            }
        }

        return new DeliveryReportResult(
                task.getId(),
                requestedLevel,
                actualLevel,
                status,
                passed,
                List.copyOf(blockingIssues),
                List.copyOf(warnings),
                new DeliveryReportResult.ReportRefs(
                        "contract_report.json",
                        BUILD_VERIFY_REPORT_FILE,
                        RUNTIME_SMOKE_TEST_REPORT_FILE
                ),
                LocalDateTime.now().toString()
        );
    }

    private List<CommandSpec> detectCommands(TaskEntity task, Path workspaceDir) {
        List<CommandSpec> commands = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        Optional<TemplateRepoService.TemplateSelection> templateSelection = resolveTemplateSelection(task);

        templateSelection.ifPresent(selection -> appendTemplateCommands(selection, workspaceDir, commands, dedupe));

        if (commands.isEmpty()) {
            Path backendDir = detectBackendDir(workspaceDir);
            if (backendDir != null) {
                addCommands(commands, dedupe, detectBackendCommands("backend", backendDir, null));
            }

            Path frontendDir = detectFrontendDir(workspaceDir);
            if (frontendDir != null) {
                addCommands(commands, dedupe, detectNodeBuildCommands("frontend", frontendDir, null));
            }
        }

        if (composeCheckEnabled && Files.exists(workspaceDir.resolve("docker-compose.yml"))) {
            addCommand(commands, dedupe, detectComposeCommand(workspaceDir));
        }
        return commands;
    }

    private Optional<TemplateRepoService.TemplateSelection> resolveTemplateSelection(TaskEntity task) {
        if (task == null || task.getTemplateId() == null || task.getTemplateId().isBlank() || templateRepoService == null) {
            return Optional.empty();
        }
        return templateRepoService.resolveTemplateById(task.getTemplateId());
    }

    private void appendTemplateCommands(
            TemplateRepoService.TemplateSelection selection,
            Path workspaceDir,
            List<CommandSpec> commands,
            Set<String> dedupe
    ) {
        Map<String, String> paths = selection.metadata() == null ? Map.of() : selection.metadata().paths();
        if (paths.containsKey("backend")) {
            Path backendDir = resolveTemplatePath(workspaceDir, paths.get("backend"));
            addCommands(commands, dedupe, detectBackendCommands("backend", backendDir, selection));
        }
        if (paths.containsKey("frontend")) {
            Path frontendDir = resolveTemplatePath(workspaceDir, paths.get("frontend"));
            addCommands(commands, dedupe, detectNodeBuildCommands("frontend", frontendDir, selection));
        }
        if (paths.containsKey("app")) {
            Path appDir = resolveTemplatePath(workspaceDir, paths.get("app"));
            addCommands(commands, dedupe, detectNodeBuildCommands("app", appDir, selection));
        }
    }

    private List<CommandSpec> detectBackendCommands(
            String scope,
            Path backendDir,
            TemplateRepoService.TemplateSelection templateSelection
    ) {
        if (backendDir == null || !Files.exists(backendDir)) {
            return List.of();
        }
        if (Files.exists(backendDir.resolve("pom.xml"))) {
            return List.of(detectMavenCommand(scope + "-maven-package", backendDir));
        }
        if (Files.exists(backendDir.resolve("build.gradle")) || Files.exists(backendDir.resolve("build.gradle.kts"))) {
            return List.of(detectGradleCommand(scope + "-gradle-build", backendDir));
        }
        if (Files.exists(backendDir.resolve("package.json"))) {
            return detectNodeBuildCommands(scope, backendDir, templateSelection);
        }
        return List.of();
    }

    private List<CommandSpec> detectNodeBuildCommands(
            String scope,
            Path projectDir,
            TemplateRepoService.TemplateSelection templateSelection
    ) {
        Path packageJsonPath = projectDir.resolve("package.json");
        if (!Files.exists(packageJsonPath)) {
            return List.of();
        }

        Map<String, String> scripts = readPackageScripts(packageJsonPath);
        if (scripts.isEmpty()) {
            return List.of();
        }

        List<CommandSpec> commands = new ArrayList<>();
        commands.add(detectNpmInstallCommand(scope + "-npm-install", projectDir));

        String buildScript = resolvePreferredBuildScript(scripts.keySet(), templateSelection);
        for (String preBuildScript : resolvePreBuildScripts(scripts.keySet(), templateSelection, buildScript)) {
            commands.add(detectNpmRunCommand(scope + "-npm-" + sanitizeName(preBuildScript), projectDir, preBuildScript));
        }
        if (buildScript != null) {
            commands.add(detectNpmRunCommand(scope + "-npm-" + sanitizeName(buildScript), projectDir, buildScript));
        }
        return commands;
    }

    private Map<String, String> readPackageScripts(Path packageJsonPath) {
        try {
            JsonNode root = objectMapper.readTree(Files.readString(packageJsonPath, StandardCharsets.UTF_8));
            JsonNode scriptsNode = root.path("scripts");
            if (!scriptsNode.isObject()) {
                return Map.of();
            }
            Map<String, String> scripts = new LinkedHashMap<>();
            scriptsNode.fields().forEachRemaining(entry -> {
                String value = entry.getValue() == null ? null : entry.getValue().asText(null);
                if (value != null && !value.isBlank()) {
                    scripts.put(entry.getKey(), value.trim());
                }
            });
            return scripts;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String resolvePreferredBuildScript(
            Set<String> availableScripts,
            TemplateRepoService.TemplateSelection templateSelection
    ) {
        if (availableScripts.contains("build")) {
            return "build";
        }
        if (availableScripts.contains("build:h5")) {
            return "build:h5";
        }

        for (String runScript : extractRunScripts(templateSelection)) {
            String mapped = mapRunScriptToBuildScript(runScript, availableScripts);
            if (mapped != null) {
                return mapped;
            }
        }

        return availableScripts.stream()
                .filter(script -> script.startsWith("build"))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private List<String> resolvePreBuildScripts(
            Set<String> availableScripts,
            TemplateRepoService.TemplateSelection templateSelection,
            String buildScript
    ) {
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        for (String runScript : extractRunScripts(templateSelection)) {
            if (!availableScripts.contains(runScript)) {
                continue;
            }
            if (runScript.equals(buildScript)) {
                continue;
            }
            if (isPreBuildScript(runScript)) {
                scripts.add(runScript);
            }
        }
        return new ArrayList<>(scripts);
    }

    private List<String> extractRunScripts(TemplateRepoService.TemplateSelection templateSelection) {
        if (templateSelection == null || templateSelection.metadata() == null || templateSelection.metadata().run() == null) {
            return List.of();
        }
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        for (String runCommand : templateSelection.metadata().run().values()) {
            if (runCommand == null || runCommand.isBlank()) {
                continue;
            }
            Matcher matcher = NPM_RUN_SCRIPT_PATTERN.matcher(runCommand);
            while (matcher.find()) {
                String script = matcher.group(1);
                if (script != null && !script.isBlank()) {
                    scripts.add(script.trim());
                }
            }
        }
        return new ArrayList<>(scripts);
    }

    private String mapRunScriptToBuildScript(String runScript, Set<String> availableScripts) {
        if (runScript == null || runScript.isBlank()) {
            return null;
        }
        if (runScript.startsWith("build") && availableScripts.contains(runScript)) {
            return runScript;
        }
        if ("dev".equals(runScript) && availableScripts.contains("build")) {
            return "build";
        }
        if (runScript.startsWith("dev:")) {
            String candidate = "build:" + runScript.substring("dev:".length());
            if (availableScripts.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isPreBuildScript(String script) {
        return "prisma:generate".equals(script)
                || "type-check".equals(script)
                || "lint".equals(script);
    }

    private CommandSpec detectMavenCommand(String name, Path workdir) {
        if (isWindows()) {
            if (Files.exists(workdir.resolve("mvnw.cmd"))) {
                return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "mvnw.cmd", "-q", "-DskipTests", "package"));
            }
            return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "mvn", "-q", "-DskipTests", "package"));
        }
        if (Files.exists(workdir.resolve("mvnw"))) {
            return new CommandSpec(sanitizeName(name), workdir, List.of("sh", "./mvnw", "-q", "-DskipTests", "package"));
        }
        return new CommandSpec(sanitizeName(name), workdir, List.of("mvn", "-q", "-DskipTests", "package"));
    }

    private CommandSpec detectGradleCommand(String name, Path workdir) {
        if (isWindows()) {
            if (Files.exists(workdir.resolve("gradlew.bat"))) {
                return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "gradlew.bat", "build", "-x", "test"));
            }
            return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "gradle", "build", "-x", "test"));
        }
        if (Files.exists(workdir.resolve("gradlew"))) {
            return new CommandSpec(sanitizeName(name), workdir, List.of("sh", "./gradlew", "build", "-x", "test"));
        }
        return new CommandSpec(sanitizeName(name), workdir, List.of("gradle", "build", "-x", "test"));
    }

    private CommandSpec detectNpmInstallCommand(String name, Path workdir) {
        if (isWindows()) {
            return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "npm", "install", "--prefer-offline"));
        }
        return new CommandSpec(sanitizeName(name), workdir, List.of("npm", "install", "--prefer-offline"));
    }

    private CommandSpec detectNpmRunCommand(String name, Path workdir, String script) {
        if (isWindows()) {
            return new CommandSpec(sanitizeName(name), workdir, List.of("cmd", "/c", "npm", "run", script));
        }
        return new CommandSpec(sanitizeName(name), workdir, List.of("npm", "run", script));
    }

    private CommandSpec detectComposeCommand(Path workdir) {
        if (isWindows()) {
            return new CommandSpec("compose-config", workdir, List.of("cmd", "/c", "docker", "compose", "config"));
        }
        return new CommandSpec("compose-config", workdir, List.of("docker", "compose", "config"));
    }

    private BuildVerifyReportResult.CommandResult executeCommand(
            CommandSpec commandSpec,
            Path workspaceDir,
            List<BuildVerifyReportResult.IssueItem> issues
    ) throws IOException {
        Path logDir = workspaceDir.resolve(".smartark").resolve("build-verify");
        Files.createDirectories(logDir);
        Path logPath = logDir.resolve(commandSpec.name() + ".log");

        ProcessBuilder processBuilder = new ProcessBuilder(commandSpec.command());
        processBuilder.directory(commandSpec.workdir().toFile());
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
                issues.add(new BuildVerifyReportResult.IssueItem(
                        "build_verify_timeout",
                        commandSpec.name() + " timed out after " + timeoutSeconds + "s",
                        relativePath(workspaceDir, logPath)
                ));
            } else {
                exitCode = process.exitValue();
                if (exitCode != 0) {
                    status = "failed";
                    issues.add(new BuildVerifyReportResult.IssueItem(
                            "build_verify_command_failed",
                            commandSpec.name() + " exited with code " + exitCode,
                            relativePath(workspaceDir, logPath)
                    ));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = "interrupted";
            issues.add(new BuildVerifyReportResult.IssueItem(
                    "build_verify_interrupted",
                    commandSpec.name() + " interrupted while running",
                    relativePath(workspaceDir, logPath)
            ));
        } catch (IOException e) {
            status = "start_failed";
            issues.add(new BuildVerifyReportResult.IssueItem(
                    "build_verify_start_failed",
                    commandSpec.name() + " failed to start: " + e.getMessage(),
                    relativePath(workspaceDir, logPath)
            ));
        }

        return new BuildVerifyReportResult.CommandResult(
                commandSpec.name(),
                String.join(" ", commandSpec.command()),
                relativePath(workspaceDir, commandSpec.workdir()),
                exitCode,
                Math.max(0L, System.currentTimeMillis() - startedAt),
                status,
                relativePath(workspaceDir, logPath)
        );
    }

    private void addCommands(List<CommandSpec> target, Set<String> dedupe, List<CommandSpec> commands) {
        for (CommandSpec command : commands) {
            addCommand(target, dedupe, command);
        }
    }

    private void addCommand(List<CommandSpec> target, Set<String> dedupe, CommandSpec command) {
        if (command == null) {
            return;
        }
        String key = command.workdir().toAbsolutePath().normalize() + "::" + String.join(" ", command.command());
        if (dedupe.add(key)) {
            target.add(command);
        }
    }

    private Path resolveTemplatePath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || ".".equals(relativePath.trim())) {
            return workspaceDir;
        }
        return workspaceDir.resolve(relativePath).normalize();
    }

    private Path detectBackendDir(Path workspaceDir) {
        List<String> candidates = List.of("backend", "services/api-gateway-java", "api", "server");
        for (String candidate : candidates) {
            Path candidatePath = workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("pom.xml"))
                    || Files.exists(candidatePath.resolve("build.gradle"))
                    || Files.exists(candidatePath.resolve("build.gradle.kts"))
                    || Files.exists(candidatePath.resolve("package.json"))) {
                return candidatePath;
            }
        }
        return null;
    }

    private Path detectFrontendDir(Path workspaceDir) {
        List<String> candidates = List.of("frontend", "frontend-mobile", "frontend-web", "web", "client", "app", ".");
        for (String candidate : candidates) {
            Path candidatePath = ".".equals(candidate) ? workspaceDir : workspaceDir.resolve(candidate);
            if (Files.exists(candidatePath.resolve("package.json"))
                    && (Files.exists(candidatePath.resolve("src"))
                    || Files.exists(candidatePath.resolve("vite.config.ts"))
                    || Files.exists(candidatePath.resolve("index.html"))
                    || Files.exists(candidatePath.resolve("next.config.ts"))
                    || Files.isDirectory(candidatePath.resolve("app")))) {
                return candidatePath;
            }
        }
        return null;
    }

    private void writeJsonFile(Path path, Object payload) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private String relativePath(Path workspaceDir, Path path) {
        String relative = workspaceDir.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private String sanitizeName(String value) {
        return value == null ? "command" : value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record BuildVerifyBundle(
            BuildVerifyReportResult buildReport,
            DeliveryReportResult deliveryReport
    ) {
    }

    private record CommandSpec(
            String name,
            Path workdir,
            List<String> command
    ) {
    }
}
