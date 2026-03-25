package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.PromptTemplateEntity;
import com.smartark.gateway.db.entity.PromptVersionEntity;
import com.smartark.gateway.prompt.PromptResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutputSchemaValidatorTest {
    @Mock
    private PromptResolver promptResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validate_shouldSkipWhenSchemaMissing() throws Exception {
        when(promptResolver.resolve("project_structure_plan")).thenReturn(Optional.empty());
        OutputSchemaValidator validator = new OutputSchemaValidator(promptResolver, objectMapper);

        OutputSchemaValidator.ValidationResult result =
                validator.validate("project_structure_plan", objectMapper.readTree("[\"README.md\"]"));

        assertTrue(result.passed());
        assertTrue(result.skipped());
    }

    @Test
    void validate_shouldFailWhenOutputSchemaInvalid() throws Exception {
        when(promptResolver.resolve("project_structure_plan")).thenReturn(Optional.of(resolvedPrompt("""
                {"type":"object","required":["files"],"properties":{"files":{"type":"array","items":{"type":"string"}}}}
                """)));
        OutputSchemaValidator validator = new OutputSchemaValidator(promptResolver, objectMapper);

        OutputSchemaValidator.ValidationResult result =
                validator.validate("project_structure_plan", objectMapper.readTree("[\"../../etc/passwd\"]"));

        assertFalse(result.passed());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void buildResponseFormat_shouldReturnJsonSchemaConfig() {
        when(promptResolver.resolve("project_structure_plan")).thenReturn(Optional.of(resolvedPrompt("""
                {"type":"array","items":{"type":"string"}}
                """)));
        OutputSchemaValidator validator = new OutputSchemaValidator(promptResolver, objectMapper);

        var responseFormat = validator.buildResponseFormat("project_structure_plan");

        assertNotNull(responseFormat);
        assertTrue(responseFormat.containsKey("json_schema"));
    }

    private PromptResolver.ResolvedPrompt resolvedPrompt(String schemaJson) {
        PromptTemplateEntity template = new PromptTemplateEntity();
        template.setId(1L);
        template.setTemplateKey("project_structure_plan");

        PromptVersionEntity version = new PromptVersionEntity();
        version.setId(1L);
        version.setTemplateId(1L);
        version.setVersionNo(1);
        version.setOutputSchemaJson(schemaJson);
        return new PromptResolver.ResolvedPrompt(template, version);
    }
}
