package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.smartark.gateway.prompt.PromptResolver;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OutputSchemaValidator {
    private final PromptResolver promptResolver;
    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;
    private final ConcurrentHashMap<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public OutputSchemaValidator(PromptResolver promptResolver, ObjectMapper objectMapper) {
        this.promptResolver = promptResolver;
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    public ValidationResult validate(String templateKey, JsonNode output) {
        Optional<JsonSchema> schemaOptional = getSchema(templateKey);
        if (schemaOptional.isEmpty()) {
            return ValidationResult.skippedResult();
        }
        try {
            List<String> errors = new ArrayList<>();
            for (ValidationMessage msg : schemaOptional.get().validate(output)) {
                errors.add(msg.getMessage());
            }
            return errors.isEmpty() ? ValidationResult.success() : ValidationResult.failedResult(errors);
        } catch (Exception e) {
            return ValidationResult.failedResult(List.of("schema_validate_exception: " + e.getMessage()));
        }
    }

    public Map<String, Object> buildResponseFormat(String templateKey) {
        return promptResolver.resolve(templateKey)
                .map(PromptResolver.ResolvedPrompt::version)
                .map(v -> v.getOutputSchemaJson())
                .filter(schema -> schema != null && !schema.isBlank())
                .map(schema -> {
                    Map<String, Object> jsonSchema = new LinkedHashMap<>();
                    jsonSchema.put("name", templateKey + "_schema");
                    try {
                        jsonSchema.put("schema", objectMapper.readTree(schema));
                    } catch (Exception e) {
                        return null;
                    }
                    jsonSchema.put("strict", true);
                    Map<String, Object> responseFormat = new LinkedHashMap<>();
                    responseFormat.put("type", "json_schema");
                    responseFormat.put("json_schema", jsonSchema);
                    return responseFormat;
                })
                .orElse(null);
    }

    public String buildCorrectivePrompt(String previousOutput, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("上一次输出未通过结构校验，请在保持有效内容的前提下仅修复以下问题，并重新输出合法JSON：\n");
        for (int i = 0; i < errors.size(); i++) {
            sb.append(i + 1).append(") ").append(errors.get(i)).append("\n");
        }
        sb.append("\n上一次输出：\n");
        sb.append(previousOutput == null ? "" : previousOutput);
        sb.append("\n\n要求：仅输出JSON，不要输出Markdown、解释或额外文本。");
        return sb.toString();
    }

    public void invalidateCache(String templateKey) {
        if (templateKey == null || templateKey.isBlank()) {
            schemaCache.clear();
            return;
        }
        schemaCache.remove(templateKey);
    }

    private Optional<JsonSchema> getSchema(String templateKey) {
        JsonSchema cached = schemaCache.get(templateKey);
        if (cached != null) {
            return Optional.of(cached);
        }
        return promptResolver.resolve(templateKey)
                .map(PromptResolver.ResolvedPrompt::version)
                .map(v -> v.getOutputSchemaJson())
                .filter(schema -> schema != null && !schema.isBlank())
                .flatMap(schemaText -> {
                    try {
                        JsonNode schemaNode = objectMapper.readTree(schemaText);
                        JsonSchema schema = schemaFactory.getSchema(schemaNode);
                        schemaCache.put(templateKey, schema);
                        return Optional.of(schema);
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                });
    }

    public record ValidationResult(boolean passed, boolean skipped, List<String> errors) {
        public static ValidationResult success() {
            return new ValidationResult(true, false, List.of());
        }

        public static ValidationResult skippedResult() {
            return new ValidationResult(true, true, List.of());
        }

        public static ValidationResult failedResult(List<String> errors) {
            return new ValidationResult(false, false, errors == null ? List.of("unknown validation error") : errors);
        }
    }
}
