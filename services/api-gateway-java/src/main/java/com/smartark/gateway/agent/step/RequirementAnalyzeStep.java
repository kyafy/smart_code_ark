package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RequirementAnalyzeStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(RequirementAnalyzeStep.class);
    
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public RequirementAnalyzeStep(ModelService modelService, ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "requirement_analyze";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Analyzing requirements and planning project structure...");
        
        JsonNode reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
        String prd = reqJson.path("prd").asText("");
        String stackBackend = reqJson.at("/stack/backend").asText("springboot");
        String stackFrontend = reqJson.at("/stack/frontend").asText("vue3");
        String stackDb = reqJson.at("/stack/db").asText("mysql");
        
        List<String> fileList = modelService.generateProjectStructure(
            prd, stackBackend, stackFrontend, stackDb, context.getInstructions()
        );
        
        context.setFileList(fileList);
        logger.info("Generated {} files in plan.", fileList.size());
    }
}
