package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import com.smartark.gateway.dto.DeliveryReportResult;
import com.smartark.gateway.dto.GenerateOptions;
import com.smartark.gateway.dto.RuntimeSmokeTestReportResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class RuntimeSmokeTestService {
    private static final String BUILD_VERIFY_REPORT_FILE = "build_verify_report.json";
    private static final String RUNTIME_SMOKE_TEST_REPORT_FILE = "runtime_smoke_test_report.json";
    private static final String DELIVERY_REPORT_FILE = "delivery_report.json";

    private final ObjectMapper objectMapper;
    private final FrontendRuntimePlanService frontendRuntimePlanService;

    @Autowired(required = false)
    private ContainerRuntimeService containerRuntimeService;

    @Value("${smartark.runtime-smoke.enabled:true}")
    private boolean runtimeSmokeEnabled;

    @Value("${smartark.runtime-smoke.command-execution-enabled:true}")
    private boolean commandExecutionEnabled;

    @Value("${smartark.runtime-smoke.health-check-timeout-seconds:60}")
    private int healthCheckTimeoutSeconds;

    @Value("${smartark.runtime-smoke.health-check-interval-ms:3000}")
    private int healthCheckIntervalMs;

    @Value("${smartark.runtime-smoke.keep-runtime-for-preview-enabled:true}")
    private boolean keepRuntimeForPreviewEnabled;

    @Value("${smartark.preview.enabled:false}")
    private boolean previewEnabled;

    @Value("${smartark.preview.auto-deploy-on-finish:true}")
    private boolean previewAutoDeployOnFinish;

    public RuntimeSmokeTestService(ObjectMapper objectMapper, FrontendRuntimePlanService frontendRuntimePlanService) {
        this.objectMapper = objectMapper;
        this.frontendRuntimePlanService = frontendRuntimePlanService;
    }

    public RuntimeSmokeTestBundle verify(TaskEntity task, Path workspaceDir) throws IOException {
        Files.createDirectories(workspaceDir);

        String requestedLevel = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());
        BuildVerifyReportResult buildReport = readBuildVerifyReport(workspaceDir);
        List<RuntimeSmokeTestReportResult.CommandResult> commands = new ArrayList<>();
        List<RuntimeSmokeTestReportResult.IssueItem> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean skipped = false;
        String smokeTarget = null;
        String startScript = null;
        RuntimeSmokeTestReportResult.ReusableRuntime reusableRuntime = null;

        if ("draft".equals(requestedLevel)) {
            skipped = true;
            warnings.add("runtime smoke test skipped for draft delivery level");
        } else if ("validated".equals(requestedLevel)) {
            skipped = true;
            warnings.add("runtime smoke test skipped for validated delivery level");
        } else if (!buildReport.passed()) {
            issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                    "runtime_smoke_prerequisite_failed",
                    "build_verify must pass before runtime smoke test can start",
                    BUILD_VERIFY_REPORT_FILE
            ));
        } else if (!runtimeSmokeEnabled) {
            issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                    "runtime_smoke_disabled",
                    "runtime smoke test is disabled by configuration",
                    null
            ));
        } else if (!commandExecutionEnabled) {
            issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                    "runtime_smoke_command_execution_disabled",
                    "runtime smoke command execution is disabled by configuration",
                    null
            ));
        } else if (containerRuntimeService == null) {
            issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                    "runtime_smoke_runtime_unavailable",
                    "container runtime service is unavailable, cannot execute runtime smoke test",
                    null
            ));
        } else {
            Optional<FrontendRuntimePlanService.FrontendRuntimePlan> smokeTargetOptional = frontendRuntimePlanService.resolvePlan(task, workspaceDir);
            if (smokeTargetOptional.isEmpty()) {
                issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                        "runtime_smoke_no_targets",
                        "no runtime smoke target found under workspace",
                        null
                ));
            } else {
                FrontendRuntimePlanService.FrontendRuntimePlan target = smokeTargetOptional.get();
                smokeTarget = relativePath(workspaceDir, target.projectDir());
                startScript = target.startScript();
                reusableRuntime = executeSmokeTarget(task.getId(), requestedLevel, target, workspaceDir, commands, issues);
            }
        }

        RuntimeSmokeTestReportResult runtimeReport = new RuntimeSmokeTestReportResult(
                issues.isEmpty(),
                skipped,
                requestedLevel,
                smokeTarget,
                startScript,
                reusableRuntime,
                List.copyOf(commands),
                List.copyOf(issues),
                List.copyOf(warnings),
                LocalDateTime.now().toString()
        );
        DeliveryReportResult deliveryReport = buildDeliveryReport(task, buildReport, runtimeReport);

        writeJsonFile(workspaceDir.resolve(RUNTIME_SMOKE_TEST_REPORT_FILE), runtimeReport);
        writeJsonFile(workspaceDir.resolve(DELIVERY_REPORT_FILE), deliveryReport);
        return new RuntimeSmokeTestBundle(buildReport, runtimeReport, deliveryReport);
    }

    private BuildVerifyReportResult readBuildVerifyReport(Path workspaceDir) throws IOException {
        Path reportPath = workspaceDir.resolve(BUILD_VERIFY_REPORT_FILE);
        if (!Files.exists(reportPath)) {
            throw new IOException(BUILD_VERIFY_REPORT_FILE + " not found");
        }
        return objectMapper.readValue(Files.readString(reportPath, StandardCharsets.UTF_8), BuildVerifyReportResult.class);
    }

    private DeliveryReportResult buildDeliveryReport(
            TaskEntity task,
            BuildVerifyReportResult buildReport,
            RuntimeSmokeTestReportResult runtimeReport
    ) {
        String requestedLevel = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());
        List<DeliveryReportResult.IssueItem> blockingIssues = new ArrayList<>();
        buildReport.blockingIssues().forEach(issue -> blockingIssues.add(new DeliveryReportResult.IssueItem(
                "build_verify",
                issue.code(),
                issue.message(),
                issue.logRef()
        )));
        runtimeReport.blockingIssues().forEach(issue -> blockingIssues.add(new DeliveryReportResult.IssueItem(
                "runtime_smoke_test",
                issue.code(),
                issue.message(),
                issue.logRef()
        )));

        List<String> warnings = new ArrayList<>(buildReport.warnings());
        warnings.addAll(runtimeReport.warnings());

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
        } else if (!buildReport.passed()) {
            actualLevel = "draft";
            status = "failed";
            passed = false;
        } else if (runtimeReport.passed()) {
            actualLevel = "deliverable";
            status = "passed";
            passed = true;
        } else {
            actualLevel = "validated";
            status = "degraded";
            passed = false;
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

    private RuntimeSmokeTestReportResult.ReusableRuntime executeSmokeTarget(
            String taskId,
            String requestedLevel,
            FrontendRuntimePlanService.FrontendRuntimePlan target,
            Path workspaceDir,
            List<RuntimeSmokeTestReportResult.CommandResult> commands,
            List<RuntimeSmokeTestReportResult.IssueItem> issues
    ) throws IOException {
        String containerId = null;
        Integer hostPort = null;
        Path logDir = workspaceDir.resolve(".smartark").resolve("runtime-smoke");
        Files.createDirectories(logDir);
        boolean keepRuntimeForPreview = shouldKeepRuntimeForPreview(requestedLevel);
        boolean retainedForPreview = false;
        Path bootLogPath = logDir.resolve("boot.log");

        try {
            hostPort = containerRuntimeService.findAvailablePort();
            containerId = containerRuntimeService.createAndStartContainer(
                    target.projectDir().toAbsolutePath().toString(),
                    hostPort,
                    taskId
            );

            ContainerRuntimeService.ExecResult installResult = containerRuntimeService.execInContainer(
                    containerId,
                    "sh", "-c", frontendRuntimePlanService.installCommand()
            );
            Path installLogPath = logDir.resolve("npm-install.log");
            saveLog(installLogPath, installResult.output());
            commands.add(toCommandResult(
                    workspaceDir,
                    "npm-install",
                    "sh -c " + frontendRuntimePlanService.installCommand(),
                    target.projectDir(),
                    installResult.exitCode(),
                    installResult.isSuccess() ? "passed" : "failed",
                    installLogPath
            ));
            if (!installResult.isSuccess()) {
                issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                        "runtime_smoke_install_failed",
                        "npm install exited with code " + installResult.exitCode(),
                        relativePath(workspaceDir, installLogPath)
                ));
                return null;
            }

            for (String preStartScript : target.preStartScripts()) {
                ContainerRuntimeService.ExecResult preStartResult = containerRuntimeService.execInContainer(
                        containerId,
                        "sh", "-c", frontendRuntimePlanService.npmRunCommand(preStartScript)
                );
                Path preStartLogPath = logDir.resolve(sanitizeName(preStartScript) + ".log");
                saveLog(preStartLogPath, preStartResult.output());
                commands.add(toCommandResult(
                        workspaceDir,
                        "npm-" + preStartScript,
                        "sh -c " + frontendRuntimePlanService.npmRunCommand(preStartScript),
                        target.projectDir(),
                        preStartResult.exitCode(),
                        preStartResult.isSuccess() ? "passed" : "failed",
                        preStartLogPath
                ));
                if (!preStartResult.isSuccess()) {
                    issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                            "runtime_smoke_command_failed",
                            preStartScript + " exited with code " + preStartResult.exitCode(),
                            relativePath(workspaceDir, preStartLogPath)
                    ));
                    return null;
                }
            }

            String bootCommand = frontendRuntimePlanService.buildBootCommand(target.startScript(), "/tmp/runtime-smoke.log");
            containerRuntimeService.execDetached(containerId, "sh", "-c", bootCommand);
            commands.add(toCommandResult(
                    workspaceDir,
                    "boot-service",
                    "sh -c " + bootCommand,
                    target.projectDir(),
                    null,
                    "started",
                    bootLogPath
            ));

            long healthStartedAt = System.currentTimeMillis();
            boolean healthy = containerRuntimeService.checkHealth(
                    "localhost",
                    hostPort,
                    healthCheckTimeoutSeconds,
                    healthCheckIntervalMs
            );
            String bootLogs = containerRuntimeService.getContainerLogs(containerId, 200);
            saveLog(bootLogPath, bootLogs);
            commands.add(new RuntimeSmokeTestReportResult.CommandResult(
                    "health-check",
                    "GET /",
                    relativePath(workspaceDir, target.projectDir()),
                    healthy ? 0 : 1,
                    Math.max(0L, System.currentTimeMillis() - healthStartedAt),
                    healthy ? "passed" : "failed",
                    relativePath(workspaceDir, bootLogPath)
            ));

            if (!healthy) {
                issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                        "runtime_smoke_health_check_failed",
                        "health check timed out after " + healthCheckTimeoutSeconds + "s",
                        relativePath(workspaceDir, bootLogPath)
                ));
                return null;
            }
            if (keepRuntimeForPreview) {
                retainedForPreview = true;
                return new RuntimeSmokeTestReportResult.ReusableRuntime(
                        true,
                        containerId,
                        hostPort,
                        relativePath(workspaceDir, target.projectDir()),
                        relativePath(workspaceDir, bootLogPath)
                );
            }
        } catch (Exception e) {
            Path errorLogPath = logDir.resolve("runtime-smoke-error.log");
            saveLog(errorLogPath, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            issues.add(new RuntimeSmokeTestReportResult.IssueItem(
                    "runtime_smoke_start_failed",
                    "runtime smoke test failed to start: " + e.getMessage(),
                    relativePath(workspaceDir, errorLogPath)
            ));
        } finally {
            if (containerId != null && !retainedForPreview) {
                containerRuntimeService.stopAndRemoveContainer(containerId);
            }
        }
        return null;
    }

    private boolean shouldKeepRuntimeForPreview(String requestedLevel) {
        return keepRuntimeForPreviewEnabled
                && "deliverable".equals(requestedLevel)
                && previewEnabled
                && previewAutoDeployOnFinish;
    }

    private RuntimeSmokeTestReportResult.CommandResult toCommandResult(
            Path workspaceDir,
            String name,
            String command,
            Path workdir,
            Integer exitCode,
            String status,
            Path logPath
    ) {
        return new RuntimeSmokeTestReportResult.CommandResult(
                sanitizeName(name),
                command,
                relativePath(workspaceDir, workdir),
                exitCode,
                0L,
                status,
                relativePath(workspaceDir, logPath)
        );
    }

    private Path resolveTemplatePath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || ".".equals(relativePath.trim())) {
            return workspaceDir;
        }
        return workspaceDir.resolve(relativePath).normalize();
    }

    private void writeJsonFile(Path path, Object payload) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private void saveLog(Path path, String content) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    private String relativePath(Path workspaceDir, Path path) {
        String relative = workspaceDir.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private String sanitizeName(String value) {
        return value == null ? "command" : value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    public record RuntimeSmokeTestBundle(
            BuildVerifyReportResult buildReport,
            RuntimeSmokeTestReportResult runtimeReport,
            DeliveryReportResult deliveryReport
    ) {
    }

}
