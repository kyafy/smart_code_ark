package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class SqlGenerateStep extends AbstractCodegenStep {

    public SqlGenerateStep(ModelService modelService, ObjectMapper objectMapper, TemplateRepoService templateRepoService) {
        super(modelService, objectMapper, templateRepoService);
    }

    @Override
    public String getStepCode() {
        return "sql_generate";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting sql generate step");
        ensureLegacyFilePlan(context);
        generateFilesByGroups(context, Set.of("database", "infra", "docs"));
    }
}
