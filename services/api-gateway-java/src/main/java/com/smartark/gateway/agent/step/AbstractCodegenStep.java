package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractCodegenStep implements AgentStep {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ModelService modelService;
    protected final ObjectMapper objectMapper;

    protected AbstractCodegenStep(ModelService modelService, ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
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

        int successCount = 0;
        int failCount = 0;
        for (FilePlanItem item : targetItems) {
            String filePath = normalizeAndValidatePath(item.getPath());
            if (filePath == null) {
                logger.warn("Skip unsafe file path: {}", item.getPath());
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
                String content = modelService.generateFileContent(
                        context.getTask().getId(),
                        context.getTask().getProjectId(),
                        prd,
                        filePath,
                        fullStack,
                        instructions,
                        groupStructure
                );
                saveFile(context, filePath, content);
                successCount++;
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
}
