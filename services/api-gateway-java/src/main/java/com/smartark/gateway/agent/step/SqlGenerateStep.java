package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

@Component
public class SqlGenerateStep extends AbstractCodegenStep {

    public SqlGenerateStep(ModelService modelService, ObjectMapper objectMapper) {
        super(modelService, objectMapper);
    }

    @Override
    public String getStepCode() {
        return "sql_generate";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Starting sql generate step");
        generateFiles(context, "database");
        generateFiles(context, "Dockerfile");
        generateFiles(context, "docker-compose");
        generateFiles(context, "README.md");
    }
}
