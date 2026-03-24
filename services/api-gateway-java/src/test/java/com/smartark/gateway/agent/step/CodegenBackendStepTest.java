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
class CodegenBackendStepTest {

    @Mock
    private ModelService modelService;

    @TempDir
    Path tempDir;

    @Test
    void execute_generatesBackendFilesOnly() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        when(modelService.generateFileContent(any(), any(), any(), eq("backend/src/main/java/App.java"), any(), any(), any()))
                .thenReturn("public class App {}");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("backend/src/main/java/App.java"))).isTrue();
        assertThat(Files.readString(tempDir.resolve("backend/src/main/java/App.java")))
                .isEqualTo("public class App {}");
        // Frontend file should NOT have been generated
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("frontend/src/App.vue"), any(), any(), any());
    }

    @Test
    void execute_skipsEmptyFilePlan() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();
        context.setFilePlan(List.of());

        step.execute(context);

        verify(modelService, never()).generateFileContent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void execute_convertLegacyFileList() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();
        context.setFilePlan(null);
        context.setFileList(List.of("backend/pom.xml", "frontend/package.json"));

        when(modelService.generateFileContent(any(), any(), any(), eq("backend/pom.xml"), any(), any(), any()))
                .thenReturn("<project/>");

        step.execute(context);

        assertThat(context.getFilePlan()).isNotNull();
        assertThat(Files.exists(tempDir.resolve("backend/pom.xml"))).isTrue();
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("frontend/package.json"), any(), any(), any());
    }

    @Test
    void execute_rejectsPathTraversal() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("../etc/passwd", "backend", 10));
        plan.add(makeItem("backend/safe.java", "backend", 20));
        context.setFilePlan(plan);

        when(modelService.generateFileContent(any(), any(), any(), eq("backend/safe.java"), any(), any(), any()))
                .thenReturn("// safe");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("backend/safe.java"))).isTrue();
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("../etc/passwd"), any(), any(), any());
    }

    @Test
    void execute_skipsTemplateManagedFilesAlreadyPresent() throws Exception {
        CodegenBackendStep step = new CodegenBackendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContextMinimal();

        Files.createDirectories(tempDir.resolve("backend/src/main/java/com/example"));
        Files.writeString(tempDir.resolve("backend/src/main/java/com/example/TemplateApplication.java"), "template");

        List<FilePlanItem> plan = new ArrayList<>();
        FilePlanItem templateItem = makeItem("backend/src/main/java/com/example/TemplateApplication.java", "backend", 10);
        templateItem.setReason("template_repo:springboot-vue3-mysql");
        plan.add(templateItem);
        context.setFilePlan(plan);

        step.execute(context);

        assertThat(Files.readString(tempDir.resolve("backend/src/main/java/com/example/TemplateApplication.java"))).isEqualTo("template");
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("backend/src/main/java/com/example/TemplateApplication.java"), any(), any(), any());
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = buildContextMinimal();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("backend/src/main/java/App.java", "backend", 10));
        plan.add(makeItem("frontend/src/App.vue", "frontend", 10));
        context.setFilePlan(plan);

        return context;
    }

    private AgentExecutionContext buildContextMinimal() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-cb");
        task.setProjectId("project-cb");
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"test app\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
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
