package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import com.smartark.gateway.dto.GenerateOptions;
import com.smartark.gateway.service.BuildVerifyService;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BuildVerifyStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(BuildVerifyStep.class);

    private final BuildVerifyService buildVerifyService;
    private final TaskRepository taskRepository;
    private final ModelService modelService;

    @Value("${smartark.build-verify.auto-fix-max-retries:2}")
    private int autoFixMaxRetries;

    @Value("${smartark.build-verify.auto-fix-enabled:true}")
    private boolean autoFixEnabled;

    // Java compilation error pattern: file path + line number + error message
    private static final Pattern JAVA_ERROR_PATTERN = Pattern.compile(
            "\\[ERROR\\].*?(/[^:]+\\.java):\\[(\\d+),\\d+\\]\\s+(.+)"
    );
    // npm/tsc error pattern: file path + line + error
    private static final Pattern TS_ERROR_PATTERN = Pattern.compile(
            "(\\S+\\.(?:ts|tsx|vue))\\((\\d+),\\d+\\):\\s+error\\s+(.+)"
    );

    public BuildVerifyStep(BuildVerifyService buildVerifyService,
                           TaskRepository taskRepository,
                           ModelService modelService) {
        this.buildVerifyService = buildVerifyService;
        this.taskRepository = taskRepository;
        this.modelService = modelService;
    }

    @Override
    public String getStepCode() {
        return "build_verify";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        TaskEntity task = context.getTask();
        String requested = GenerateOptions.normalizeDeliveryLevel(task.getDeliveryLevelRequested());

        BuildVerifyService.BuildVerifyBundle bundle = buildVerifyService.verify(task, context.getWorkspaceDir());
        updateTaskDelivery(task, bundle);

        // Auto-fix loop: if build failed and not draft, try LLM fix
        if (autoFixEnabled && !"draft".equals(requested) && !bundle.buildReport().passed()) {
            for (int attempt = 1; attempt <= autoFixMaxRetries; attempt++) {
                context.logInfo("Build auto-fix attempt " + attempt + "/" + autoFixMaxRetries);
                boolean anyFixed = tryFixBuildErrors(context, bundle, task);
                if (!anyFixed) {
                    context.logWarn("Build auto-fix: no files could be fixed, stopping retry loop");
                    break;
                }
                // Re-run build verification
                bundle = buildVerifyService.verify(task, context.getWorkspaceDir());
                updateTaskDelivery(task, bundle);

                if (bundle.buildReport().passed()) {
                    context.logInfo("Build auto-fix succeeded on attempt " + attempt);
                    break;
                }
            }
        }

        logResult(context, bundle);

        if (!"draft".equals(requested) && !bundle.buildReport().passed()) {
            throw new BusinessException(
                    ErrorCodes.BUILD_VERIFY_FAILED,
                    "build verify failed: requested=" + requested
                            + ", actual=" + bundle.deliveryReport().deliveryLevelActual()
                            + ", status=" + bundle.deliveryReport().status()
            );
        }
    }

    private void updateTaskDelivery(TaskEntity task, BuildVerifyService.BuildVerifyBundle bundle) {
        task.setDeliveryLevelActual(bundle.deliveryReport().deliveryLevelActual());
        task.setDeliveryStatus(bundle.deliveryReport().status());
        taskRepository.save(task);
    }

    private void logResult(AgentExecutionContext context, BuildVerifyService.BuildVerifyBundle bundle) {
        context.logInfo("Build verify result: requested=" + bundle.deliveryReport().deliveryLevelRequested()
                + ", actual=" + bundle.deliveryReport().deliveryLevelActual()
                + ", status=" + bundle.deliveryReport().status()
                + ", passed=" + bundle.deliveryReport().passed());
    }

    private boolean tryFixBuildErrors(AgentExecutionContext context,
                                      BuildVerifyService.BuildVerifyBundle bundle,
                                      TaskEntity task) {
        boolean anyFixed = false;
        Path workspaceDir = context.getWorkspaceDir();
        String techStack = extractTechStack(context);

        for (BuildVerifyReportResult.CommandResult cmd : bundle.buildReport().commands()) {
            if ("passed".equals(cmd.status())) {
                continue;
            }
            String logRef = cmd.logRef();
            if (logRef == null || logRef.isBlank()) {
                continue;
            }
            Path logPath = workspaceDir.resolve(logRef);
            if (!Files.isRegularFile(logPath)) {
                continue;
            }
            try {
                String logContent = readLogTail(logPath, 200);
                String errorFilePath = extractErrorFilePath(logContent, workspaceDir);
                if (errorFilePath == null) {
                    logger.info("Could not extract error file path from build log: {}", logRef);
                    continue;
                }

                Path sourceFile = workspaceDir.resolve(errorFilePath);
                if (!Files.isRegularFile(sourceFile)) {
                    logger.info("Error file not found in workspace: {}", errorFilePath);
                    continue;
                }

                String currentContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
                context.logInfo("Build auto-fix: attempting to fix " + errorFilePath);
                String fixedContent = modelService.fixCompilationError(
                        task.getId(), task.getProjectId(),
                        errorFilePath, currentContent, logContent, techStack
                );
                if (fixedContent != null && !fixedContent.isBlank() && !fixedContent.startsWith("//")) {
                    Files.writeString(sourceFile, fixedContent, StandardCharsets.UTF_8);
                    context.logInfo("Build auto-fix: patched " + errorFilePath);
                    anyFixed = true;
                }
            } catch (Exception e) {
                logger.warn("Build auto-fix failed for log {}: {}", logRef, e.getMessage());
            }
        }
        return anyFixed;
    }

    private String extractErrorFilePath(String logContent, Path workspaceDir) {
        // Try Java error pattern
        Matcher javaMatcher = JAVA_ERROR_PATTERN.matcher(logContent);
        if (javaMatcher.find()) {
            String absolutePath = javaMatcher.group(1);
            return toRelativePath(absolutePath, workspaceDir);
        }

        // Try TypeScript error pattern
        Matcher tsMatcher = TS_ERROR_PATTERN.matcher(logContent);
        if (tsMatcher.find()) {
            return tsMatcher.group(1);
        }

        // Fallback: look for common error indicators with file paths
        Pattern genericPattern = Pattern.compile("(?:error|ERROR|Error).*?([\\w/.-]+\\.(?:java|ts|tsx|vue|js|jsx))");
        Matcher genericMatcher = genericPattern.matcher(logContent);
        if (genericMatcher.find()) {
            return genericMatcher.group(1);
        }

        return null;
    }

    private String toRelativePath(String absolutePath, Path workspaceDir) {
        try {
            Path absPath = Path.of(absolutePath).normalize();
            Path wsNorm = workspaceDir.toAbsolutePath().normalize();
            if (absPath.startsWith(wsNorm)) {
                return wsNorm.relativize(absPath).toString().replace('\\', '/');
            }
        } catch (Exception ignored) {
        }
        // Return as-is if can't relativize — might already be relative
        return absolutePath.replace('\\', '/');
    }

    private String readLogTail(Path logPath, int maxLines) throws IOException {
        var lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        int start = Math.max(0, lines.size() - maxLines);
        return String.join("\n", lines.subList(start, lines.size()));
    }

    private String extractTechStack(AgentExecutionContext context) {
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
            return "Backend: " + reqJson.at("/stack/backend").asText("springboot")
                    + ", Frontend: " + reqJson.at("/stack/frontend").asText("vue3")
                    + ", Database: " + reqJson.at("/stack/db").asText("mysql");
        } catch (Exception e) {
            return "";
        }
    }
}
