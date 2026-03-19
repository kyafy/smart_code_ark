package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public abstract class AbstractCodegenStep implements AgentStep {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected final ModelService modelService;
    protected final ObjectMapper objectMapper;

    protected AbstractCodegenStep(ModelService modelService, ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    protected void generateFiles(AgentExecutionContext context, String keyword) throws Exception {
        String prd = getPrd(context);
        String fullStack = getFullStack(context);
        List<String> fileList = context.getFileList();
        String instructions = context.getInstructions();
        
        if (fileList == null) {
            logger.warn("File list is empty, skip generating files for keyword: {}", keyword);
            return;
        }

        for (String filePath : fileList) {
            if (filePath.contains(keyword) || ("database".equals(keyword) && filePath.endsWith(".sql"))) {
                logger.info("Generating file: {}", filePath);
                String content = modelService.generateFileContent(prd, filePath, fullStack, instructions);
                saveFile(context, filePath, content);
            }
        }
    }

    protected void saveFile(AgentExecutionContext context, String filePath, String content) throws IOException {
        Path fullPath = context.getWorkspaceDir().resolve(filePath);
        Files.createDirectories(fullPath.getParent());
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
