package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JeecgCodegenClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void buildJeecgHints_engineDirectShouldNotFallbackCodeFromTemplateId() throws Exception {
        JeecgCodegenClient client = new JeecgCodegenClient(objectMapper);
        AgentExecutionContext context = mockContext(
                "tpl-fallback-should-not-be-code",
                "{\"title\":\"demo\",\"jeecg\":{\"engine\":{\"tableName\":\"demo_order\",\"moduleName\":\"demo\"}}}"
        );
        TemplateRepoService.TemplateSelection selection = mockSelection("jeecg-default-one");

        JsonNode spec = objectMapper.readTree(context.getSpec().getRequirementJson());
        Map<String, Object> hints = ReflectionTestUtils.invokeMethod(
                client,
                "buildJeecgHints",
                spec,
                context,
                selection
        );

        assertThat(hints).isNotNull();
        assertThat(hints).doesNotContainKey("code");
        assertThat(hints.get("mode")).isEqualTo("engine_direct");
        assertThat(hints.get("engine")).isInstanceOf(Map.class);
        assertThat(hints.get("projectPath")).isEqualTo(context.getWorkspaceDir().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildJeecgHints_shouldSupportNestedCodegenJeecgNode() throws Exception {
        JeecgCodegenClient client = new JeecgCodegenClient(objectMapper);
        AgentExecutionContext context = mockContext(
                "tpl-any",
                "{\"title\":\"demo\",\"codegen\":{\"jeecg\":{\"code\":\"form-001\"}}}"
        );
        TemplateRepoService.TemplateSelection selection = mockSelection("jeecg-default-one");

        JsonNode spec = objectMapper.readTree(context.getSpec().getRequirementJson());
        Map<String, Object> hints = ReflectionTestUtils.invokeMethod(
                client,
                "buildJeecgHints",
                spec,
                context,
                selection
        );

        assertThat(hints).isNotNull();
        assertThat(hints.get("code")).isEqualTo("form-001");
        assertThat(hints.get("mode")).isEqualTo("online_compat");
    }

    private AgentExecutionContext mockContext(String templateId, String requirementJson) {
        TaskEntity task = new TaskEntity();
        task.setId("t1");
        task.setProjectId("p1");
        task.setTemplateId(templateId);

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setProjectId("p1");
        spec.setRequirementJson(requirementJson);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setSpec(spec);
        context.setWorkspaceDir(tempDir.resolve("workspace"));
        return context;
    }

    private TemplateRepoService.TemplateSelection mockSelection(String templateKey) {
        TemplateRepoService.TemplateMetadata metadata = new TemplateRepoService.TemplateMetadata(
                templateKey,
                templateKey,
                "jeecg-online",
                "test",
                Map.of("app", "upstream"),
                Map.of("backend", "jeecg-online", "frontend", "jeecg-online", "db", "mysql"),
                Map.of(),
                Map.of()
        );
        return new TemplateRepoService.TemplateSelection(
                templateKey,
                tempDir.resolve("template"),
                "backend",
                "frontend",
                metadata
        );
    }
}
