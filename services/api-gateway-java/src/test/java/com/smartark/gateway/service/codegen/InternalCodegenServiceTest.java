package com.smartark.gateway.service.codegen;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.TemplateRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCodegenServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void tryRender_shouldSkipWhenEngineIsLlm() {
        InternalCodegenService service = buildService(List.of());
        CodegenRenderResult result = service.tryRender("llm", null, mockSelection());
        assertThat(result.invoked()).isFalse();
        assertThat(result.success()).isFalse();
        assertThat(result.provider()).isEqualTo("internal");
    }

    @Test
    void tryRender_internalServiceShouldUseStrictProviderOrder() {
        InternalCodegenService service = buildService(List.of(
                provider("mock", new CodegenRenderResult(true, true, "mock", "ok", List.of("backend/A.java")))
        ));
        ReflectionTestUtils.setField(service, "strictProviderOrder", "mock");

        CodegenRenderResult result = service.tryRender("internal_service", null, mockSelection());
        assertThat(result.invoked()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.files()).contains("backend/A.java");
    }

    @Test
    void tryRender_hybridShouldFallbackToNextProvider() {
        InternalCodegenService service = buildService(List.of(
                provider("jeecg", new CodegenRenderResult(false, true, "jeecg", "upstream failed", List.of())),
                provider("mock", new CodegenRenderResult(true, true, "mock", "fallback ok", List.of("frontend/App.vue")))
        ));
        ReflectionTestUtils.setField(service, "hybridProviderOrder", "jeecg,mock");

        CodegenRenderResult result = service.tryRender("hybrid", null, mockSelection());
        assertThat(result.invoked()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("mock");
        assertThat(result.message()).contains("fallback");
    }

    @Test
    void tryRender_jeecgRuleShouldForceJeecgProvider() {
        List<String> calledProviders = new ArrayList<>();
        InternalCodegenService service = buildService(List.of(
                trackingProvider("local_template", calledProviders,
                        new CodegenRenderResult(true, true, "local_template", "local ok", List.of("backend/A.java"))),
                trackingProvider("jeecg", calledProviders,
                        new CodegenRenderResult(true, true, "jeecg", "jeecg ok", List.of("backend/B.java")))
        ));
        ReflectionTestUtils.setField(service, "strictProviderOrder", "local_template,jeecg");

        CodegenRenderResult result = service.tryRender("jeecg_rule", null, mockSelection());
        assertThat(result.invoked()).isTrue();
        assertThat(result.success()).isTrue();
        assertThat(result.provider()).isEqualTo("jeecg");
        assertThat(result.files()).contains("backend/B.java");
        assertThat(calledProviders).containsExactly("jeecg");
    }

    private InternalCodegenService buildService(List<CodegenProvider> providers) {
        InternalCodegenService service = new InternalCodegenService(providers);
        ReflectionTestUtils.setField(service, "providerOrder", "jeecg");
        ReflectionTestUtils.setField(service, "hybridProviderOrder", "jeecg");
        ReflectionTestUtils.setField(service, "strictProviderOrder", "jeecg");
        return service;
    }

    private TemplateRepoService.TemplateSelection mockSelection() {
        Path templateRoot = tempDir.resolve("template");
        try {
            Files.createDirectories(templateRoot);
        } catch (Exception ignored) {
        }
        TemplateRepoService.TemplateMetadata metadata = new TemplateRepoService.TemplateMetadata(
                "mock-template",
                "mock-template",
                "test",
                "test",
                Map.of("backend", "backend", "frontend", "frontend"),
                Map.of("backend", "springboot", "frontend", "vue3", "db", "mysql"),
                Map.of(),
                Map.of()
        );
        return new TemplateRepoService.TemplateSelection(
                "mock-template",
                templateRoot,
                "backend",
                "frontend",
                metadata
        );
    }

    private CodegenProvider provider(String key, CodegenRenderResult result) {
        return new CodegenProvider() {
            @Override
            public String providerKey() {
                return key;
            }

            @Override
            public CodegenRenderResult tryRender(AgentExecutionContext context,
                                                 TemplateRepoService.TemplateSelection selection,
                                                 String codegenEngine) {
                return result;
            }
        };
    }

    private CodegenProvider trackingProvider(String key, List<String> calledProviders, CodegenRenderResult result) {
        return new CodegenProvider() {
            @Override
            public String providerKey() {
                return key;
            }

            @Override
            public CodegenRenderResult tryRender(AgentExecutionContext context,
                                                 TemplateRepoService.TemplateSelection selection,
                                                 String codegenEngine) {
                calledProviders.add(key);
                return result;
            }
        };
    }
}
