package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlGenerateStepTest {

    @Mock
    private ModelService modelService;

    @TempDir
    Path tempDir;

    @Test
    void execute_processesAllThreeGroups() throws Exception {
        SqlGenerateStep step = new SqlGenerateStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        when(modelService.generateFileContent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("-- generated content");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("database/V1__init.sql"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("docker-compose.yml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("docs/README.md"))).isTrue();
        // Backend should NOT be processed
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("backend/src/main/java/App.java"), any(), any(), any());
    }

    @Test
    void execute_dockerComposeClassifiedAsInfra() throws Exception {
        SqlGenerateStep step = new SqlGenerateStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContextMinimal();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("docker-compose.yml", "infra", 10));
        plan.add(makeItem("scripts/start.sh", "infra", 20));
        context.setFilePlan(plan);

        when(modelService.generateFileContent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("# infra content");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("docker-compose.yml"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("scripts/start.sh"))).isTrue();
    }

    @Test
    void execute_readmeMdClassifiedAsDocs() throws Exception {
        SqlGenerateStep step = new SqlGenerateStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContextMinimal();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("docs/deploy.md", "docs", 10));
        context.setFilePlan(plan);

        when(modelService.generateFileContent(any(), any(), any(), eq("docs/deploy.md"), any(), any(), any()))
                .thenReturn("# Deploy Guide");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("docs/deploy.md"))).isTrue();
        assertThat(Files.readString(tempDir.resolve("docs/deploy.md"))).isEqualTo("# Deploy Guide");
    }

    @Test
    void execute_skipsTemplateManagedInfraAndDocsWhenAlreadyPresent() throws Exception {
        SqlGenerateStep step = new SqlGenerateStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContextMinimal();

        Files.createDirectories(tempDir.resolve("docs"));
        Files.createDirectories(tempDir.resolve("scripts"));
        Files.writeString(tempDir.resolve("docs/deploy.md"), "template-doc");
        Files.writeString(tempDir.resolve("scripts/start.sh"), "template-script");

        List<FilePlanItem> plan = new ArrayList<>();
        FilePlanItem docsItem = makeItem("docs/deploy.md", "docs", 10);
        docsItem.setReason("template_repo:springboot-vue3-mysql");
        FilePlanItem scriptItem = makeItem("scripts/start.sh", "infra", 20);
        scriptItem.setReason("template_repo:springboot-vue3-mysql");
        plan.add(docsItem);
        plan.add(scriptItem);
        context.setFilePlan(plan);

        step.execute(context);

        assertThat(Files.readString(tempDir.resolve("docs/deploy.md"))).isEqualTo("template-doc");
        assertThat(Files.readString(tempDir.resolve("scripts/start.sh"))).isEqualTo("template-script");
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("docs/deploy.md"), any(), any(), any());
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("scripts/start.sh"), any(), any(), any());
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = buildContextMinimal();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("backend/src/main/java/App.java", "backend", 10));
        plan.add(makeItem("frontend/src/App.vue", "frontend", 10));
        plan.add(makeItem("database/V1__init.sql", "database", 10));
        plan.add(makeItem("docker-compose.yml", "infra", 20));
        plan.add(makeItem("docs/README.md", "docs", 30));
        context.setFilePlan(plan);

        return context;
    }

    private AgentExecutionContext buildContextMinimal() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-sql");
        task.setProjectId("project-sql");
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"test\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        return context;
    }

    private FilePlanItem makeItem(String path, String group, int priority) {
        FilePlanItem item = new FilePlanItem();
        item.setPath(path);
        item.setGroup(group);
        item.setPriority(priority);
        return item;
    }
}
