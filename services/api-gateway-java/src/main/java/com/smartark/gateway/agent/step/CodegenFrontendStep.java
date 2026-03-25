package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CodegenFrontendStep extends AbstractCodegenStep {

    public CodegenFrontendStep(ModelService modelService, ObjectMapper objectMapper, TemplateRepoService templateRepoService) {
        this(modelService, objectMapper, templateRepoService, null, false);
    }

    @Autowired
    public CodegenFrontendStep(ModelService modelService,
                               ObjectMapper objectMapper,
                               TemplateRepoService templateRepoService,
                               LangchainRuntimeGraphClient runtimeGraphClient,
                               @Value("${smartark.langchain.runtime.codegen-graph-enabled:false}") boolean runtimeCodegenGraphEnabled) {
        super(modelService, objectMapper, templateRepoService, runtimeGraphClient, runtimeCodegenGraphEnabled);
    }

    @Override
    public String getStepCode() {
        return "codegen_frontend";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting codegen frontend step");
        ensureLegacyFilePlan(context);
        generateFilesByGroups(context, Set.of("frontend"));
    }
}
