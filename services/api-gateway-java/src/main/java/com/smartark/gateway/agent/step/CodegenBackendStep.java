package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

@Component
public class CodegenBackendStep extends AbstractCodegenStep {

    public CodegenBackendStep(ModelService modelService, ObjectMapper objectMapper) {
        super(modelService, objectMapper);
    }

    @Override
    public String getStepCode() {
        return "codegen_backend";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting codegen backend step");
        generateFiles(context, "backend");
    }
}
