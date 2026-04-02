package com.smartark.gateway.service.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.db.entity.ProjectSpecEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalTemplateRuleProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void tryRender_shouldRewritePathAndExtension() throws Exception {
        Path workspace = tempDir.resolve("workspace-path-rewrite");
        Files.createDirectories(workspace);

        String javaTemplatePath = "upstream/default/one/java/${bussiPackage}/${entityPackage}/entity/${entityName}.javai";
        String vueTemplatePath = "upstream/default/one/vue/modules/${entityName}List.vuei";
        writeFile(workspace, javaTemplatePath, """
                package ${bussiPackage}.${entityPackage}.entity;
                public class ${entityName} {
                  String table = "${tableName}";
                }
                """);
        writeFile(workspace, vueTemplatePath, "<template><div>${displayName}</div></template>");

        AgentExecutionContext context = buildContext(workspace, """
                {
                  "title":"Order Center",
                  "projectType":"admin",
                  "jeecg":{
                    "moduleName":"order_mgmt",
                    "bussiPackage":"com.acme.codegen",
                    "entityPackage":"order",
                    "entityName":"OrderMain",
                    "tableName":"biz_order"
                  }
                }
                """);

        LocalTemplateRuleProvider provider = buildProvider();
        CodegenRenderResult result = provider.tryRender(context, mockSelection(workspace), "internal_service");

        assertThat(result.invoked()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("local_template");
        assertThat(result.files()).contains(
                "upstream/default/one/java/com/acme/codegen/order/entity/OrderMain.java",
                "upstream/default/one/vue/modules/OrderMainList.vue"
        );
        assertThat(Files.exists(workspace.resolve(javaTemplatePath))).isFalse();
        assertThat(Files.exists(workspace.resolve(vueTemplatePath))).isFalse();
        assertThat(Files.exists(workspace.resolve("upstream/default/one/java/com/acme/codegen/order/entity/OrderMain.java"))).isTrue();
        assertThat(Files.exists(workspace.resolve("upstream/default/one/vue/modules/OrderMainList.vue"))).isTrue();
    }

    @Test
    void tryRender_shouldRewriteKnownContentVarsAndKeepUnknownFreemarkerVars() throws Exception {
        Path workspace = tempDir.resolve("workspace-content-rewrite");
        Files.createDirectories(workspace);

        String templatePath = "upstream/common/rules/${moduleName}/rule-${tableName}.ftl";
        writeFile(workspace, templatePath, """
                entity=${entityName}
                table=${tableName}
                keep=${po.fieldName}
                project=__PROJECT_NAME__
                display=__DISPLAY_NAME__
                """);

        AgentExecutionContext context = buildContext(workspace, """
                {
                  "title":"Sales Portal",
                  "projectType":"admin",
                  "jeecg":{
                    "moduleName":"sales-center",
                    "bussiPackage":"com.smartark.generated",
                    "entityPackage":"sales",
                    "entityName":"SalesOrder",
                    "tableName":"sales_order"
                  }
                }
                """);

        LocalTemplateRuleProvider provider = buildProvider();
        CodegenRenderResult result = provider.tryRender(context, mockSelection(workspace), "internal_service");

        Path rewritten = workspace.resolve("upstream/common/rules/sales_center/rule-sales_order.ftl");
        assertThat(result.success()).isTrue();
        assertThat(result.files()).contains("upstream/common/rules/sales_center/rule-sales_order.ftl");
        assertThat(Files.exists(rewritten)).isTrue();

        String content = Files.readString(rewritten, StandardCharsets.UTF_8);
        assertThat(content).contains("entity=SalesOrder");
        assertThat(content).contains("table=sales_order");
        assertThat(content).contains("keep=${po.fieldName}");
        assertThat(content).contains("project=sales-portal");
        assertThat(content).contains("display=Sales Portal");
    }

    private LocalTemplateRuleProvider buildProvider() {
        LocalTemplateRuleProvider provider = new LocalTemplateRuleProvider(new ObjectMapper());
        ReflectionTestUtils.setField(provider, "localTemplateEnabled", true);
        ReflectionTestUtils.setField(provider, "pathRewriteEnabled", true);
        ReflectionTestUtils.setField(provider, "contentRewriteEnabled", true);
        ReflectionTestUtils.setField(provider, "extensionRewriteEnabled", true);
        ReflectionTestUtils.setField(provider, "overwriteTarget", true);
        return provider;
    }

    private AgentExecutionContext buildContext(Path workspace, String requirementJson) {
        TaskEntity task = new TaskEntity();
        task.setId("task-1");
        task.setProjectId("project-1");
        task.setTemplateId("jeecg-default-one");

        ProjectSpecEntity spec = new ProjectSpecEntity();
        spec.setRequirementJson(requirementJson);

        AgentExecutionContext context = new AgentExecutionContext();
        context.setTask(task);
        context.setSpec(spec);
        context.setWorkspaceDir(workspace);
        return context;
    }

    private TemplateRepoService.TemplateSelection mockSelection(Path templateRoot) {
        TemplateRepoService.TemplateMetadata metadata = new TemplateRepoService.TemplateMetadata(
                "jeecg-default-one",
                "jeecg-default-one",
                "jeecg-online",
                "mock",
                Map.of("app", "upstream"),
                Map.of("backend", "springboot", "frontend", "vue3", "db", "mysql"),
                Map.of(),
                Map.of()
        );
        return new TemplateRepoService.TemplateSelection(
                "jeecg-default-one",
                templateRoot,
                "backend",
                "frontend",
                metadata
        );
    }

    private void writeFile(Path workspace, String relativePath, String content) throws Exception {
        Path file = workspace.resolve(relativePath);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
