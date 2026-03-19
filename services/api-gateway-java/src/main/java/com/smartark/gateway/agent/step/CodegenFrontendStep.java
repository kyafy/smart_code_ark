package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

@Component
public class CodegenFrontendStep extends AbstractCodegenStep {

    public CodegenFrontendStep(ModelService modelService, ObjectMapper objectMapper) {
        super(modelService, objectMapper);
    }

    @Override
    public String getStepCode() {
        return "codegen_frontend";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting codegen frontend step");
        generateFiles(context, "frontend");
    }
}
