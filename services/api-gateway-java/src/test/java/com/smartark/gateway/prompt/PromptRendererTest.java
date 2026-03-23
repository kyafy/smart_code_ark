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
}
