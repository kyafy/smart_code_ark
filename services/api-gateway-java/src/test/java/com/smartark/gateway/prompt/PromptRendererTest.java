package com.smartark.gateway.prompt;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    @Test
    void render_replacesAllVariables() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("prd", "Build a todo app");
        vars.put("filePath", "backend/App.java");

        String result = renderer.render("PRD: {{prd}}, File: {{filePath}}", vars);

        assertThat(result).isEqualTo("PRD: Build a todo app, File: backend/App.java");
    }

    @Test
    void render_handlesNullValues() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("prd", null);
        vars.put("filePath", "App.java");

        String result = renderer.render("PRD: {{prd}}, File: {{filePath}}", vars);

        assertThat(result).isEqualTo("PRD: , File: App.java");
    }

    @Test
    void render_preservesUnknownPlaceholders() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("prd", "test");

        String result = renderer.render("PRD: {{prd}}, Unknown: {{unknown}}", vars);

        assertThat(result).isEqualTo("PRD: test, Unknown: {{unknown}}");
    }

    @Test
    void render_handlesEmptyTemplate() {
        String result = renderer.render("", Map.of("key", "value"));
        assertThat(result).isEmpty();
    }

    @Test
    void render_handlesNullTemplate() {
        String result = renderer.render(null, Map.of("key", "value"));
        assertThat(result).isEmpty();
    }

    @Test
    void render_handlesEmptyVariablesMap() {
        String result = renderer.render("Hello {{name}}", Map.of());
        assertThat(result).isEqualTo("Hello {{name}}");
    }

    @Test
    void render_handlesMultipleOccurrencesOfSameVariable() {
        Map<String, String> vars = Map.of("name", "Ark");

        String result = renderer.render("{{name}} is {{name}}", vars);

        assertThat(result).isEqualTo("Ark is Ark");
    }

    @Test
    void render_structurePlanSnapshot_containsDeliveryContractRules() {
        String template = """
                PRD：{{prd}}
                后端：{{stackBackend}}
                前端：{{stackFrontend}}
                数据库：{{stackDb}}
                附加说明：{{instructions}}
                输出要求：
                - 至少 24 个文件路径；
                - 必须包含 docs/deploy.md、scripts/deploy.sh、scripts/start.sh；
                - 路径必须为相对路径，禁止绝对路径与 ..；
                - 必须包含 docker-compose.yml；
                - 结果只返回 JSON 数组。
                """;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("prd", "电商订单系统");
        vars.put("stackBackend", "springboot");
        vars.put("stackFrontend", "vue3");
        vars.put("stackDb", "mysql");
        vars.put("instructions", "需要完整部署能力");

        String rendered = renderer.render(template, vars);
        String expected = """
                PRD：电商订单系统
                后端：springboot
                前端：vue3
                数据库：mysql
                附加说明：需要完整部署能力
                输出要求：
                - 至少 24 个文件路径；
                - 必须包含 docs/deploy.md、scripts/deploy.sh、scripts/start.sh；
                - 路径必须为相对路径，禁止绝对路径与 ..；
                - 必须包含 docker-compose.yml；
                - 结果只返回 JSON 数组。
                """;

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered)
                .contains("docs/deploy.md")
                .contains("scripts/deploy.sh")
                .contains("scripts/start.sh")
                .contains("禁止绝对路径与 ..");
    }

    @Test
    void render_fileGenerationSnapshot_containsStartupAndPathRules() {
        String template = """
                任务：生成文件 {{filePath}}
                业务背景：{{prd}}
                规则：
                1) 部署三件套必须存在：docs/deploy.md、scripts/deploy.sh、scripts/start.sh
                2) docker-compose.yml 的 build.context 必须指向真实存在目录
                3) 路径禁止出现 ..
                4) 关键启动文件必须可执行并包含 docker compose up --build -d
                """;
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("filePath", "scripts/start.sh");
        vars.put("prd", "SaaS 运营后台");

        String rendered = renderer.render(template, vars);
        String expected = """
                任务：生成文件 scripts/start.sh
                业务背景：SaaS 运营后台
                规则：
                1) 部署三件套必须存在：docs/deploy.md、scripts/deploy.sh、scripts/start.sh
                2) docker-compose.yml 的 build.context 必须指向真实存在目录
                3) 路径禁止出现 ..
                4) 关键启动文件必须可执行并包含 docker compose up --build -d
                """;

        assertThat(rendered).isEqualTo(expected);
        assertThat(rendered)
                .contains("部署三件套")
                .contains("路径禁止出现 ..")
                .contains("docker compose up --build -d");
    }
}
