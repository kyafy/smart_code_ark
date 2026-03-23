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
class CodegenFrontendStepTest {

    @Mock
    private ModelService modelService;

    @TempDir
    Path tempDir;

    @Test
    void execute_generatesFrontendFilesOnly() throws Exception {
        CodegenFrontendStep step = new CodegenFrontendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        when(modelService.generateFileContent(any(), any(), any(), eq("frontend/src/App.vue"), any(), any()))
                .thenReturn("<template><div>Hello</div></template>");
        when(modelService.generateFileContent(any(), any(), any(), eq("frontend/src/main.ts"), any(), any()))
                .thenReturn("import { createApp } from 'vue'");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("frontend/src/App.vue"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("frontend/src/main.ts"))).isTrue();
        // Backend file should NOT have been generated
        verify(modelService, never())
                .generateFileContent(any(), any(), any(), eq("backend/src/main/java/App.java"), any(), any());
    }

    @Test
    void execute_handlesVueAndReactPaths() throws Exception {
        CodegenFrontendStep step = new CodegenFrontendStep(modelService, new ObjectMapper());
        AgentExecutionContext context = buildContext();

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("frontend/src/App.vue", "frontend", 10));
        plan.add(makeItem("frontend/src/components/Header.tsx", "frontend", 20));
        context.setFilePlan(plan);

        when(modelService.generateFileContent(any(), any(), any(), any(), any(), any()))
                .thenReturn("// content");

        step.execute(context);

        assertThat(Files.exists(tempDir.resolve("frontend/src/App.vue"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("frontend/src/components/Header.tsx"))).isTrue();
    }

    private AgentExecutionContext buildContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-cf");
        task.setProjectId("project-cf");
        context.setTask(task);
        context.setWorkspaceDir(tempDir);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson("{\"prd\":\"test app\",\"stack\":{\"backend\":\"springboot\",\"frontend\":\"vue3\",\"db\":\"mysql\"}}");
        context.setSpec(spec);

        List<FilePlanItem> plan = new ArrayList<>();
        plan.add(makeItem("backend/src/main/java/App.java", "backend", 10));
        plan.add(makeItem("frontend/src/App.vue", "frontend", 10));
        plan.add(makeItem("frontend/src/main.ts", "frontend", 20));
        context.setFilePlan(plan);

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
