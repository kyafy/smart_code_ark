package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.StepMemoryService;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequirementAnalyzeStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private StepMemoryService stepMemoryService;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @TempDir
    Path tempDir;

    @Test
    void execute_usesRuntimeCodegenGraphWhenEnabled() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RequirementAnalyzeStep step = new RequirementAnalyzeStep(
                modelService,
                objectMapper,
                stepMemoryService,
                templateRepoService(),
                runtimeGraphClient,
                true
        );

        AgentExecutionContext context = buildContext();
        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-codegen-1",
                "task-runtime-req",
                "codegen",
                "completed",
                Map.of(
                        "structure_json",
                        objectMapper.readTree("""
                                {
                                  "files": [
                                    "README.md",
                                    "docker-compose.yml",
                                    "scripts/start.sh",
                                    "scripts/deploy.sh",
                                    "docs/deploy.md",
                                    "backend/src/main/resources/application.yml",
                                    "backend/src/main/java/com/example/Application.java",
                                    "frontend/src/main.ts",
                                    "frontend/src/App.vue",
                                    "database/schema.sql",
                                    "backend/src/main/java/com/example/order/OrderController.java",
                                    "frontend/src/views/OrderList.vue"
                                  ]
                                }
                                """)
                )
        );

        when(runtimeGraphClient.runCodegenGraph(eq("task-runtime-req"), eq("project-runtime-req"), eq(32L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-runtime-req"), eq("project-runtime-req"), eq(32L), any(Map.class));
        verify(modelService, never()).generateProjectStructure(any(), any(), any(), any(), any(), any(), any());
        assertTrue(context.getFileList().contains("backend/pom.xml"));
        assertTrue(context.getFileList().contains("frontend/package.json"));
        assertTrue(context.getFileList().contains("backend/src/main/java/com/example/order/OrderController.java"));
        assertTrue(context.getFileList().contains("frontend/src/views/OrderList.vue"));
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-req");
        task.setProjectId("project-runtime-req");
        task.setUserId(32L);
        context.setTask(task);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"title\":\"Demo\",\"prd\":\"test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);
        context.setInstructions("{}");
        context.setWorkspaceDir(tempDir.resolve("workspace-runtime-req"));
        return context;
    }

    private TemplateRepoService templateRepoService() throws Exception {
        Path repoRoot = tempDir.resolve("template-repo");
        Files.createDirectories(repoRoot.resolve("templates/springboot-vue3-mysql/backend"));
        Files.createDirectories(repoRoot.resolve("templates/springboot-vue3-mysql/frontend"));
        Files.writeString(
                repoRoot.resolve("catalog.json"),
                """
                {
                  "version": "1.0.0",
                  "templates": [
                    {
                      "key": "springboot-vue3-mysql",
                      "name": "Spring Boot + Vue 3 + MySQL",
                      "paths": {
                        "backend": "backend",
                        "frontend": "frontend"
                      }
                    }
                  ]
                }
                """
        );
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/backend/pom.xml"), "<project>demo</project>");
        Files.writeString(repoRoot.resolve("templates/springboot-vue3-mysql/frontend/package.json"), "{\"name\":\"demo\"}");
        Files.writeString(
                repoRoot.resolve("templates/springboot-vue3-mysql/template.json"),
                """
                {
                  "key": "springboot-vue3-mysql",
                  "name": "Spring Boot + Vue 3 + MySQL",
                  "stack": {
                    "backend": "Spring Boot 3",
                    "frontend": "Vue 3 + Vite",
                    "database": "MySQL 8"
                  }
                }
                """
        );
        return new TemplateRepoService(new ObjectMapper(), repoRoot.toString());
    }
}
