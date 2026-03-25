package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlGenerateStepRuntimeTest {

    @Mock
    private ModelService modelService;
    @Mock
    private TemplateRepoService templateRepoService;
    @Mock
    private LangchainRuntimeGraphClient runtimeGraphClient;

    @TempDir
    Path tempDir;

    @Test
    void execute_usesRuntimeCodegenGraphWhenEnabled() throws Exception {
        SqlGenerateStep step = new SqlGenerateStep(
                modelService,
                new ObjectMapper(),
                templateRepoService,
                runtimeGraphClient,
                true
        );
        AgentExecutionContext context = buildContext();

        LangchainGraphRunResult runtimeResult = new LangchainGraphRunResult(
                "run-sql-1",
                "task-runtime-sql",
                "codegen",
                "completed",
                Map.of(
                        "codegen_files",
                        List.of(
                                Map.of(
                                        "path", "database/schema.sql",
                                        "content", "CREATE TABLE demo(id BIGINT PRIMARY KEY);"
                                ),
                                Map.of(
                                        "path", "docs/deploy.md",
                                        "content", "# Deploy runtime"
                                ),
                                Map.of(
                                        "path", "scripts/start.sh",
                                        "content", "#!/usr/bin/env bash\necho runtime"
                                )
                        )
                )
        );

        when(runtimeGraphClient.runCodegenGraph(eq("task-runtime-sql"), eq("project-runtime-sql"), eq(18L), any(Map.class)))
                .thenReturn(runtimeResult);

        step.execute(context);

        verify(runtimeGraphClient).runCodegenGraph(eq("task-runtime-sql"), eq("project-runtime-sql"), eq(18L), any(Map.class));
        verify(modelService, never()).generateFileContent(any(), any(), any(), any(), any(), any(), any());
        assertThat(Files.readString(tempDir.resolve("database/schema.sql"))).contains("CREATE TABLE");
        assertThat(Files.readString(tempDir.resolve("docs/deploy.md"))).contains("# Deploy runtime");
        assertThat(Files.readString(tempDir.resolve("scripts/start.sh"))).contains("runtime");
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-runtime-sql");
        task.setProjectId("project-runtime-sql");
        task.setUserId(18L);
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"sql test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        FilePlanItem schema = new FilePlanItem();
        schema.setPath("database/schema.sql");
        schema.setGroup("database");
        schema.setPriority(10);
        FilePlanItem deploy = new FilePlanItem();
        deploy.setPath("docs/deploy.md");
        deploy.setGroup("docs");
        deploy.setPriority(20);
        FilePlanItem start = new FilePlanItem();
        start.setPath("scripts/start.sh");
        start.setGroup("infra");
        start.setPriority(30);
        context.setFilePlan(List.of(schema, deploy, start));
        return context;
    }
}
