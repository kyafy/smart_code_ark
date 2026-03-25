package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.TemplateRepoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractCodegenStep implements AgentStep {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ModelService modelService;
    protected final ObjectMapper objectMapper;
    protected final TemplateRepoService templateRepoService;
    protected final LangchainRuntimeGraphClient runtimeGraphClient;
    protected final boolean runtimeCodegenGraphEnabled;

    protected AbstractCodegenStep(ModelService modelService, ObjectMapper objectMapper, TemplateRepoService templateRepoService) {
        this(modelService, objectMapper, templateRepoService, null, false);
    }

    protected AbstractCodegenStep(ModelService modelService,
                                  ObjectMapper objectMapper,
                                  TemplateRepoService templateRepoService,
                                  LangchainRuntimeGraphClient runtimeGraphClient,
                                  boolean runtimeCodegenGraphEnabled) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.templateRepoService = templateRepoService;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimeCodegenGraphEnabled = runtimeCodegenGraphEnabled;
    }

    protected void generateFilesByGroup(AgentExecutionContext context, Set<String> groups) throws Exception {
        String prd = getPrd(context);
        String fullStack = getFullStack(context);
        List<FilePlanItem> filePlan = context.getFilePlan();
        String instructions = context.getNormalizedInstructions() != null ? context.getNormalizedInstructions() : context.getInstructions();
        String groupStructure = buildGroupStructure(filePlan, groups);

        if (filePlan == null || filePlan.isEmpty()) {
            logger.warn("File plan is empty, skip generating files for groups: {}", groups);
            context.logWarn("File plan is empty, skip codegen for groups: " + groups);
            return;
        }
        long groupFileCount = filePlan.stream()
                .filter(item -> item.getPath() != null && !item.getPath().isBlank())
                .filter(item -> item.getGroup() != null && groups.contains(item.getGroup()))
                .count();
        context.logInfo("Codegen start: groups=" + groups + ", fileCount=" + groupFileCount
                + ", projectStructureLength=" + groupStructure.length());

        List<FilePlanItem> targetItems = filePlan.stream()
                .filter(item -> item.getPath() != null && !item.getPath().isBlank())
                .filter(item -> item.getGroup() != null && groups.contains(item.getGroup()))
                .sorted(Comparator.comparing(item -> item.getPriority() == null ? 50 : item.getPriority()))
                .toList();
        Set<String> runtimeGeneratedPaths = generateFilesByRuntimeGraph(
                context, targetItems, groups, prd, fullStack, instructions, groupStructure
        );

        int successCount = 0;
        int failCount = 0;
        for (FilePlanItem item : targetItems) {
            String filePath = normalizeAndValidatePath(item.getPath());
            if (filePath == null) {
                logger.warn("Skip unsafe file path: {}", item.getPath());
                continue;
            }
            if (runtimeGeneratedPaths.contains(filePath)) {
                logger.info("Skip model generation for runtime-generated file: {}", filePath);
                successCount++;
                continue;
            }
            if (isTemplateManaged(item) && templateFileAlreadyPresent(context, filePath)) {
                logger.info("Skip template-managed file already materialized: {}", filePath);
                context.logInfo("Codegen skip template-managed file: " + filePath);
                successCount++;
                continue;
            }
            try {
                logger.info("Generating file: {} [group={}]", filePath, item.getGroup());
                String content;
                TemplateRepoService.TemplateSelection tplSelection = context.getTemplateSelection();
                if (tplSelection != null) {
                    TemplateRepoService.ExampleContext exCtx = templateRepoService.resolveExampleContext(tplSelection, filePath);
                    if (exCtx.hasExample()) {
                        content = modelService.generateFileContentWithTemplate(
                                context.getTask().getId(),
                                context.getTask().getProjectId(),
                                prd, filePath, fullStack, instructions, groupStructure,
                                exCtx.primaryExample(), exCtx.relatedExamples()
                        );
                    } else {
                        content = modelService.generateFileContent(
                                context.getTask().getId(),
                                context.getTask().getProjectId(),
                                prd, filePath, fullStack, instructions, groupStructure
                        );
                    }
                } else {
                    content = modelService.generateFileContent(
                            context.getTask().getId(),
                            context.getTask().getProjectId(),
                            prd, filePath, fullStack, instructions, groupStructure
                    );
                }
                saveFile(context, filePath, content);
                successCount++;

                // Co-generate unit test if template is available and file is testable
                if (tplSelection != null) {
                    tryGenerateTestFile(context, tplSelection, filePath, content, prd, fullStack, instructions, groupStructure);
                }
            } catch (Exception e) {
                failCount++;
                logger.error("Failed to generate file: {} [group={}], error={}", filePath, item.getGroup(), e.getMessage());
                context.logWarn("Codegen file failed: " + filePath + ", error=" + e.getMessage());
            }
        }
        context.logInfo("Codegen completed: groups=" + groups + ", success=" + successCount + ", failed=" + failCount);
        if (successCount == 0 && !targetItems.isEmpty()) {
            throw new RuntimeException("All file generation failed for groups: " + groups);
        }
    }

    private Set<String> generateFilesByRuntimeGraph(AgentExecutionContext context,
                                                    List<FilePlanItem> targetItems,
                                                    Set<String> groups,
                                                    String prd,
                                                    String fullStack,
                                                    String instructions,
                                                    String groupStructure) {
        if (!runtimeCodegenGraphEnabled || runtimeGraphClient == null || targetItems == null || targetItems.isEmpty()) {
            return Set.of();
        }
        List<String> targetPaths = targetItems.stream()
                .map(item -> normalizeAndValidatePath(item.getPath()))
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        if (targetPaths.isEmpty()) {
            return Set.of();
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", getStepCode());
            input.put("prd", prd == null ? "" : prd);
            input.put("fullStack", fullStack == null ? "" : fullStack);
            input.put("instructions", instructions == null ? "" : instructions);
            input.put("groupStructure", groupStructure == null ? "" : groupStructure);
            input.put("groups", new ArrayList<>(groups));
            input.put("targetFiles", targetPaths);

            LangchainGraphRunResult result = runtimeGraphClient.runCodegenGraph(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    context.getTask().getUserId(),
                    input
            );
            JsonNode resultNode = objectMapper.valueToTree(result == null ? Map.of() : result.result());
            Map<String, String> runtimeFiles = extractRuntimeFileContents(resultNode);
            if (runtimeFiles.isEmpty()) {
                logger.warn("Runtime graph returned empty file payload for step={}, fallback to model-service", getStepCode());
                return Set.of();
            }
            Set<String> allowedPaths = new LinkedHashSet<>(targetPaths);
            Set<String> generatedPaths = new LinkedHashSet<>();
            for (Map.Entry<String, String> entry : runtimeFiles.entrySet()) {
                String filePath = normalizeAndValidatePath(entry.getKey());
                if (filePath == null || !allowedPaths.contains(filePath)) {
                    continue;
                }
                String content = entry.getValue();
                if (content == null || content.isBlank()) {
                    continue;
                }
                try {
                    saveFile(context, filePath, content);
                    generatedPaths.add(filePath);
                } catch (Exception e) {
                    logger.warn("Runtime-generated file save failed: path={}, error={}", filePath, e.getMessage());
                }
            }
            if (!generatedPaths.isEmpty()) {
                logger.info("Codegen step {} generated {} files via runtime graph", getStepCode(), generatedPaths.size());
                context.logInfo("Codegen routed to langchain runtime graph: step=" + getStepCode()
                        + ", generated=" + generatedPaths.size());
            }
            return generatedPaths;
        } catch (Exception e) {
            logger.warn("Runtime codegen graph failed for step={}, fallback to model-service: {}", getStepCode(), e.getMessage());
            return Set.of();
        }
    }

    private Map<String, String> extractRuntimeFileContents(JsonNode resultNode) {
        Map<String, String> files = new LinkedHashMap<>();
        if (resultNode == null || resultNode.isNull()) {
            return files;
        }
        collectRuntimeFilesFromArray(files, resultNode.path("codegen_files"));
        collectRuntimeFilesFromArray(files, resultNode.path("generated_files"));
        collectRuntimeFilesFromArray(files, resultNode.path("files"));
        JsonNode fileContents = resultNode.path("file_contents");
        if (fileContents.isObject()) {
            fileContents.fields().forEachRemaining(entry -> {
                String content = entry.getValue().asText("");
                if (!content.isBlank()) {
                    files.putIfAbsent(entry.getKey(), content);
                }
            });
        }
        return files;
    }

    private void collectRuntimeFilesFromArray(Map<String, String> files, JsonNode node) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            String path = item.path("path").asText("");
            if (path.isBlank()) {
                path = item.path("filePath").asText("");
            }
            if (path.isBlank()) {
                path = item.path("file").asText("");
            }
            String content = item.path("content").asText("");
            if (content.isBlank()) {
                content = item.path("code").asText("");
            }
            if (content.isBlank()) {
                content = item.path("text").asText("");
            }
            if (!path.isBlank() && !content.isBlank()) {
                files.putIfAbsent(path, content);
            }
        }
    }

    private String buildGroupStructure(List<FilePlanItem> filePlan, Set<String> groups) {
        if (filePlan == null || filePlan.isEmpty()) {
            return "";
        }
        return filePlan.stream()
                .filter(item -> item.getPath() != null && !item.getPath().isBlank())
                .filter(item -> item.getGroup() != null && groups.contains(item.getGroup()))
                .map(FilePlanItem::getPath)
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    private String normalizeAndValidatePath(String input) {
        String value = input == null ? "" : input.trim().replace("\\", "/");
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.isBlank()) {
            return null;
        }
        if (value.startsWith("/") || value.contains("..")) {
            return null;
        }
        return value;
    }

    protected void generateFiles(AgentExecutionContext context, String keyword) throws Exception {
        generateFilesByGroup(context, Set.of(keyword));
    }

    protected void generateFilesByGroups(AgentExecutionContext context, Set<String> groups) throws Exception {
        generateFilesByGroup(context, groups);
    }

    protected void ensureLegacyFilePlan(AgentExecutionContext context) {
        if (context.getFilePlan() == null && context.getFileList() != null) {
            context.setFilePlan(context.getFileList().stream().map(path -> {
                FilePlanItem item = new FilePlanItem();
                item.setPath(path);
                item.setGroup(detectGroup(path));
                item.setPriority(50);
                return item;
            }).toList());
        }
    }

    protected String detectGroup(String path) {
        return FileGroupDetector.detect(path);
    }

    private boolean isTemplateManaged(FilePlanItem item) {
        return item != null && item.getReason() != null && item.getReason().startsWith("template_repo:");
    }

    private boolean templateFileAlreadyPresent(AgentExecutionContext context, String filePath) {
        if (context.getWorkspaceDir() == null) {
            return false;
        }
        return Files.exists(context.getWorkspaceDir().resolve(filePath).normalize());
    }

    protected void saveFile(AgentExecutionContext context, String filePath, String content) throws IOException {
        Path fullPath = context.getWorkspaceDir().resolve(filePath).normalize();
        if (!fullPath.startsWith(context.getWorkspaceDir().normalize())) {
            throw new IOException("Invalid file path out of workspace: " + filePath);
        }
        if (fullPath.getParent() != null) {
            Files.createDirectories(fullPath.getParent());
        } else {
            Files.createDirectories(context.getWorkspaceDir());
        }
        Files.writeString(fullPath, content, StandardCharsets.UTF_8);
        logger.info("Saved file: {}", fullPath);
    }

    protected String getPrd(AgentExecutionContext context) throws Exception {
        JsonNode reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
        return reqJson.path("prd").asText("");
    }

    protected String getFullStack(AgentExecutionContext context) throws Exception {
        JsonNode reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
        String stackBackend = reqJson.at("/stack/backend").asText("springboot");
        String stackFrontend = reqJson.at("/stack/frontend").asText("vue3");
        String stackDb = reqJson.at("/stack/db").asText("mysql");
        return "Backend: " + stackBackend + ", Frontend: " + stackFrontend + ", Database: " + stackDb;
    }

    private void tryGenerateTestFile(AgentExecutionContext context,
                                     TemplateRepoService.TemplateSelection tplSelection,
                                     String sourceFilePath, String sourceContent,
                                     String prd, String fullStack, String instructions, String groupStructure) {
        String testPath = computeTestPath(sourceFilePath);
        if (testPath == null) {
            return;
        }
        try {
            TemplateRepoService.ExampleContext testExCtx = templateRepoService.resolveTestExampleContext(
                    tplSelection, testPath, sourceContent);
            if (!testExCtx.hasExample()) {
                return;
            }
            logger.info("Co-generating test file: {} for source: {}", testPath, sourceFilePath);
            context.logInfo("Codegen co-generating test: " + testPath);
            String testContent = modelService.generateFileContentWithTemplate(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    prd, testPath, fullStack, instructions, groupStructure,
                    testExCtx.primaryExample(), testExCtx.relatedExamples()
            );
            saveFile(context, testPath, testContent);
            logger.info("Test file generated: {}", testPath);
        } catch (Exception e) {
            logger.warn("Failed to co-generate test for {}: {}", sourceFilePath, e.getMessage());
            context.logWarn("Test co-generation failed for " + sourceFilePath + ": " + e.getMessage());
        }
    }

    /**
     * Compute test file path from source file path.
     * Returns null if the file type should not have a test.
     */
    static String computeTestPath(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            return null;
        }
        String path = sourceFilePath.replace('\\', '/');

        // Backend Java: only Service and Controller get tests
        if (path.endsWith("Service.java") && !path.endsWith("Repository.java")) {
            // src/main/java/...Service.java → src/test/java/...ServiceTest.java
            return path.replace("/src/main/java/", "/src/test/java/")
                       .replace("Service.java", "ServiceTest.java");
        }
        if (path.endsWith("Controller.java")) {
            return path.replace("/src/main/java/", "/src/test/java/")
                       .replace("Controller.java", "ControllerTest.java");
        }

        // Frontend Vue pages get tests
        if (path.endsWith(".vue") && path.contains("/pages/")) {
            // frontend/src/pages/UserPage.vue → frontend/src/__tests__/UserPage.test.ts
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String baseName = fileName.replace(".vue", "");
            String dir = path.substring(0, path.lastIndexOf("/pages/"));
            return dir + "/src/__tests__/" + baseName + ".test.ts";
        }

        // Python FastAPI: backend/app/main.py → backend/tests/test_main.py
        if (path.endsWith(".py") && path.contains("/app/")) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            // Skip __init__.py, config, database infrastructure files
            if (fileName.startsWith("__") || fileName.equals("config.py") || fileName.equals("database.py")) {
                return null;
            }
            String baseName = fileName.replace(".py", "");
            String dir = path.substring(0, path.indexOf("/app/"));
            return dir + "/tests/test_" + baseName + ".py";
        }

        // Python Django: backend/users/views.py → backend/users/tests.py
        // Django convention: one tests.py per app
        if (path.endsWith("views.py") && !path.contains("/app/")) {
            String dir = path.substring(0, path.lastIndexOf('/'));
            return dir + "/tests.py";
        }

        // Next.js pages: app/page.tsx → __tests__/page.test.tsx
        if (path.endsWith(".tsx") && path.contains("/app/") && path.contains("page.tsx")) {
            // Handle both root and nested: app/page.tsx, app/todos/page.tsx
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            String baseName = fileName.replace(".tsx", "");
            // Determine if it's inside frontend/ or at root
            int appIdx = path.indexOf("/app/");
            String prefix = appIdx > 0 ? path.substring(0, appIdx) + "/" : "";
            return prefix + "__tests__/" + baseName + ".test.tsx";
        }

        return null;
    }
}
