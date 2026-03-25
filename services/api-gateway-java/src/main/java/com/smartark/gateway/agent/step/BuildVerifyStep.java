package com.smartark.gateway.agent.step;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.dto.BuildVerifyReportResult;
import com.smartark.gateway.dto.GenerateOptions;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.BuildVerifyService;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class BuildVerifyStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(BuildVerifyStep.class);

    private final BuildVerifyService buildVerifyService;
    private final TaskRepository taskRepository;
    private final ModelService modelService;
    private final LangchainRuntimeGraphClient runtimeGraphClient;
    private final boolean runtimeCodegenGraphEnabled;

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
        this(buildVerifyService, taskRepository, modelService, null, false);
    }

    @Autowired
    public BuildVerifyStep(BuildVerifyService buildVerifyService,
                           TaskRepository taskRepository,
                           ModelService modelService,
                           LangchainRuntimeGraphClient runtimeGraphClient,
                           @Value("${smartark.langchain.runtime.codegen-graph-enabled:false}") boolean runtimeCodegenGraphEnabled) {
        this.buildVerifyService = buildVerifyService;
        this.taskRepository = taskRepository;
        this.modelService = modelService;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimeCodegenGraphEnabled = runtimeCodegenGraphEnabled;
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
        List<BuildErrorCandidate> candidates = collectBuildErrorCandidates(context, bundle);
        if (candidates.isEmpty()) {
            return false;
        }
        Set<String> fixedPaths = new LinkedHashSet<>();
        String techStack = extractTechStack(context);
        Map<String, String> runtimeBatchFixed = fixBuildErrorsByRuntimeBatch(context, task, techStack, candidates);
        for (BuildErrorCandidate candidate : candidates) {
            String normalizedPath = normalizePath(candidate.filePath());
            if (normalizedPath == null || normalizedPath.isBlank()) {
                continue;
            }
            String fixedContent = runtimeBatchFixed.get(normalizedPath);
            if (fixedContent == null || fixedContent.isBlank()) {
                continue;
            }
            try {
                writeFixedContent(context, candidate.filePath(), fixedContent);
                fixedPaths.add(normalizedPath);
                context.logInfo("Build auto-fix: patched " + candidate.filePath() + " by runtime batch");
            } catch (Exception e) {
                logger.warn("Build runtime batch patch failed for {}: {}", candidate.filePath(), e.getMessage());
            }
        }

        boolean anyFixed = !fixedPaths.isEmpty();
        for (BuildErrorCandidate candidate : candidates) {
            String normalizedPath = normalizePath(candidate.filePath());
            if (normalizedPath != null && fixedPaths.contains(normalizedPath)) {
                continue;
            }
            try {
                context.logInfo("Build auto-fix: attempting to fix " + candidate.filePath());
                String fixedContent = fixCompilationErrorWithRuntimeFallback(
                        context,
                        task.getId(), task.getProjectId(),
                        candidate.filePath(), candidate.currentContent(), candidate.buildLog(), techStack
                );
                if (fixedContent != null && !fixedContent.isBlank() && !fixedContent.startsWith("//")) {
                    writeFixedContent(context, candidate.filePath(), fixedContent);
                    context.logInfo("Build auto-fix: patched " + candidate.filePath());
                    anyFixed = true;
                }
            } catch (Exception e) {
                logger.warn("Build auto-fix failed for {}: {}", candidate.filePath(), e.getMessage());
            }
        }
        return anyFixed;
    }

    private List<BuildErrorCandidate> collectBuildErrorCandidates(AgentExecutionContext context,
                                                                  BuildVerifyService.BuildVerifyBundle bundle) {
        Path workspaceDir = context.getWorkspaceDir();
        Map<String, BuildErrorCandidate> candidates = new LinkedHashMap<>();
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
                List<String> errorFilePaths = extractErrorFilePaths(logContent, workspaceDir);
                if (errorFilePaths.isEmpty()) {
                    logger.info("Could not extract error file path from build log: {}", logRef);
                    continue;
                }
                for (String errorFilePath : errorFilePaths) {
                    String normalizedPath = normalizePath(errorFilePath);
                    if (normalizedPath == null || normalizedPath.isBlank() || candidates.containsKey(normalizedPath)) {
                        continue;
                    }
                    Path sourceFile = workspaceDir.resolve(normalizedPath);
                    if (!Files.isRegularFile(sourceFile)) {
                        logger.info("Error file not found in workspace: {}", normalizedPath);
                        continue;
                    }
                    String currentContent = Files.readString(sourceFile, StandardCharsets.UTF_8);
                    candidates.put(normalizedPath, new BuildErrorCandidate(normalizedPath, currentContent, logContent));
                }
            } catch (Exception e) {
                logger.warn("Build auto-fix candidate collection failed for log {}: {}", logRef, e.getMessage());
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private Map<String, String> fixBuildErrorsByRuntimeBatch(AgentExecutionContext context,
                                                             TaskEntity task,
                                                             String techStack,
                                                             List<BuildErrorCandidate> candidates) {
        if (!runtimeCodegenGraphEnabled || runtimeGraphClient == null || candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        try {
            List<Map<String, Object>> filesInput = new ArrayList<>();
            for (BuildErrorCandidate candidate : candidates) {
                filesInput.add(Map.of(
                        "filePath", candidate.filePath(),
                        "currentContent", candidate.currentContent(),
                        "buildLog", candidate.buildLog()
                ));
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "build_verify_batch_autofix");
            input.put("techStack", techStack == null ? "" : techStack);
            input.put("files", filesInput);

            LangchainGraphRunResult result = runtimeGraphClient.runCodegenGraph(
                    task.getId(),
                    task.getProjectId(),
                    task.getUserId(),
                    input
            );
            Map<String, String> fixedFiles = extractRuntimeFixedFiles(result);
            if (fixedFiles.isEmpty()) {
                logger.warn("Runtime graph returned empty build batch autofix payload, fallback to single-file flow: taskId={}",
                        task.getId());
                return Map.of();
            }
            logger.info("Build auto-fix batch generated by runtime graph: taskId={}, fileCount={}", task.getId(), fixedFiles.size());
            context.logInfo("Build auto-fix batch routed to langchain runtime graph successfully. fixedCount=" + fixedFiles.size());
            return fixedFiles;
        } catch (Exception e) {
            logger.warn("Runtime build batch auto-fix failed, fallback to single-file flow: taskId={}, error={}",
                    task.getId(), e.getMessage());
            return Map.of();
        }
    }

    private Map<String, String> extractRuntimeFixedFiles(LangchainGraphRunResult result) {
        if (result == null || result.result() == null) {
            return Map.of();
        }
        Object payload = result.result();
        if (!(payload instanceof Map<?, ?> resultMap)) {
            return Map.of();
        }
        Map<String, String> fixedFiles = new LinkedHashMap<>();
        Object fixedFileList = resultMap.get("fixed_files");
        if (fixedFileList instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String path = normalizePath(asText(map.get("path")));
                if (path == null || path.isBlank()) {
                    path = normalizePath(asText(map.get("file_path")));
                }
                String content = asText(map.get("fixed_content"));
                if (content == null || content.isBlank()) {
                    content = asText(map.get("content"));
                }
                if (path != null && !path.isBlank() && content != null && !content.isBlank()) {
                    fixedFiles.put(path, content);
                }
            }
        }
        Object fileContents = resultMap.get("file_contents");
        if (fileContents instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String path = normalizePath(asText(entry.getKey()));
                String content = asText(entry.getValue());
                if (path != null && !path.isBlank() && content != null && !content.isBlank()) {
                    fixedFiles.putIfAbsent(path, content);
                }
            }
        }
        return fixedFiles;
    }

    private void writeFixedContent(AgentExecutionContext context, String relativePath, String fixedContent) throws IOException {
        Path sourceFile = context.getWorkspaceDir().resolve(relativePath);
        if (!Files.isRegularFile(sourceFile)) {
            return;
        }
        Files.writeString(sourceFile, fixedContent, StandardCharsets.UTF_8);
    }

    private String fixCompilationErrorWithRuntimeFallback(AgentExecutionContext context,
                                                          String taskId,
                                                          String projectId,
                                                          String filePath,
                                                          String currentContent,
                                                          String buildLog,
                                                          String techStack) {
        String runtimeFixed = fixCompilationErrorFromRuntime(context, taskId, projectId, filePath, currentContent, buildLog, techStack);
        if (runtimeFixed != null && !runtimeFixed.isBlank()) {
            return runtimeFixed;
        }
        return modelService.fixCompilationError(taskId, projectId, filePath, currentContent, buildLog, techStack);
    }

    private String fixCompilationErrorFromRuntime(AgentExecutionContext context,
                                                  String taskId,
                                                  String projectId,
                                                  String filePath,
                                                  String currentContent,
                                                  String buildLog,
                                                  String techStack) {
        if (!runtimeCodegenGraphEnabled || runtimeGraphClient == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "build_verify_autofix");
            input.put("filePath", filePath == null ? "" : filePath);
            input.put("currentContent", currentContent == null ? "" : currentContent);
            input.put("buildLog", buildLog == null ? "" : buildLog);
            input.put("techStack", techStack == null ? "" : techStack);

            LangchainGraphRunResult result = runtimeGraphClient.runCodegenGraph(
                    taskId,
                    projectId,
                    context.getTask().getUserId(),
                    input
            );
            String fixedContent = extractRuntimeFixedContent(result, filePath);
            if (fixedContent == null || fixedContent.isBlank()) {
                logger.warn("Runtime graph returned empty build autofix payload, fallback to model-service: taskId={}, file={}",
                        taskId, filePath);
                return null;
            }
            logger.info("Build auto-fix generated by runtime graph: taskId={}, file={}", taskId, filePath);
            context.logInfo("Build auto-fix routed to langchain runtime graph successfully.");
            return fixedContent;
        } catch (Exception e) {
            logger.warn("Runtime build auto-fix failed, fallback to model-service: taskId={}, file={}, error={}",
                    taskId, filePath, e.getMessage());
            return null;
        }
    }

    private String extractRuntimeFixedContent(LangchainGraphRunResult result, String expectedFilePath) {
        if (result == null || result.result() == null) {
            return null;
        }
        Object payload = result.result();
        if (!(payload instanceof Map<?, ?> resultMap)) {
            return null;
        }
        String direct = asText(resultMap.get("fixed_content"));
        if (direct == null || direct.isBlank()) {
            direct = asText(resultMap.get("fixedContent"));
        }
        if (direct != null && !direct.isBlank()) {
            return direct;
        }

        Object fileContents = resultMap.get("file_contents");
        if (fileContents instanceof Map<?, ?> map) {
            String hit = findContentByPathMap(map, expectedFilePath);
            if (hit != null && !hit.isBlank()) {
                return hit;
            }
        }

        Object codegenFiles = resultMap.get("codegen_files");
        if (codegenFiles instanceof List<?> list) {
            String hit = findContentByFileList(list, expectedFilePath);
            if (hit != null && !hit.isBlank()) {
                return hit;
            }
        }
        return null;
    }

    private String findContentByPathMap(Map<?, ?> map, String expectedFilePath) {
        String normalizedExpected = normalizePath(expectedFilePath);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = normalizePath(asText(entry.getKey()));
            if (normalizedExpected == null || normalizedExpected.equals(key)) {
                String value = asText(entry.getValue());
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String findContentByFileList(List<?> list, String expectedFilePath) {
        String normalizedExpected = normalizePath(expectedFilePath);
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String path = normalizePath(asText(map.get("path")));
            if (path == null || (normalizedExpected != null && !normalizedExpected.equals(path))) {
                continue;
            }
            String content = asText(map.get("content"));
            if (content != null && !content.isBlank()) {
                return content;
            }
        }
        return null;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        return path.trim().replace('\\', '/');
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private List<String> extractErrorFilePaths(String logContent, Path workspaceDir) {
        Set<String> paths = new LinkedHashSet<>();
        Matcher javaMatcher = JAVA_ERROR_PATTERN.matcher(logContent);
        while (javaMatcher.find()) {
            String absolutePath = javaMatcher.group(1);
            String relativePath = normalizePath(toRelativePath(absolutePath, workspaceDir));
            if (relativePath != null && !relativePath.isBlank()) {
                paths.add(relativePath);
            }
        }

        Matcher tsMatcher = TS_ERROR_PATTERN.matcher(logContent);
        while (tsMatcher.find()) {
            String relativePath = normalizePath(tsMatcher.group(1));
            if (relativePath != null && !relativePath.isBlank()) {
                paths.add(relativePath);
            }
        }

        Pattern genericPattern = Pattern.compile("(?:error|ERROR|Error).*?([\\w/.-]+\\.(?:java|ts|tsx|vue|js|jsx))");
        Matcher genericMatcher = genericPattern.matcher(logContent);
        while (genericMatcher.find()) {
            String relativePath = normalizePath(genericMatcher.group(1));
            if (relativePath != null && !relativePath.isBlank()) {
                paths.add(relativePath);
            }
        }

        return new ArrayList<>(paths);
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

    private record BuildErrorCandidate(String filePath, String currentContent, String buildLog) {
    }
}
