package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementAnalyzeStepTest {

    @Mock
    private ModelService modelService;

    @Test
    void execute_fallbackWhenModelStructureFailed() throws Exception {
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "生成项目结构失败"));

        step.execute(context);

        assertFalse(context.getFileList().isEmpty());
        assertTrue(context.getFileList().stream().anyMatch(p -> p.endsWith("README.md")));
        assertFalse(context.getFilePlan().isEmpty());
    }

    @Test
    void execute_sanitizeUnsafePaths() throws Exception {
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        when(modelService.generateProjectStructure(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of("", "   ", "/etc/passwd", "../hack.sh", "backend\\src\\main.ts", "frontend/package.json"));

        step.execute(context);

        assertTrue(context.getFileList().contains("backend/src/main.ts"));
        assertTrue(context.getFileList().contains("frontend/package.json"));
        assertFalse(context.getFileList().stream().anyMatch(p -> p.startsWith("/") || p.contains("..")));
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-r");
        task.setProjectId("project-r");
        context.setTask(task);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);
        context.setInstructions("{}");
        return context;
    }
}
