package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CodegenBackendStep extends AbstractCodegenStep {

    public CodegenBackendStep(ModelService modelService, ObjectMapper objectMapper, TemplateRepoService templateRepoService) {
        super(modelService, objectMapper, templateRepoService);
    }

    @Override
    public String getStepCode() {
        return "codegen_backend";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting codegen backend step");
        ensureLegacyFilePlan(context);
        generateFilesByGroups(context, Set.of("backend"));
    }
}
