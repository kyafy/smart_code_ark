package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class JeecgCodegenClient {
    private final ObjectMapper objectMapper;

    @Value("${smartark.codegen.jeecg.enabled:false}")
    private boolean jeecgEnabled;

    @Value("${smartark.codegen.jeecg.base-url:http://localhost:19090}")
    private String jeecgBaseUrl;

    @Value("${smartark.codegen.jeecg.render-path:/api/codegen/jeecg/render}")
    private String jeecgRenderPath;

    @Value("${smartark.codegen.jeecg.timeout-ms:8000}")
    private int timeoutMs;

    public JeecgCodegenClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JeecgRenderResult tryRender(
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection
    ) {
        if (!jeecgEnabled) {
            return new JeecgRenderResult(false, false, "jeecg codegen is disabled", List.of());
        }
        if (context == null || context.getTask() == null || context.getSpec() == null || selection == null) {
            return new JeecgRenderResult(false, false, "invalid context or template selection", List.of());
        }

        try {
            JsonNode spec = objectMapper.readTree(context.getSpec().getRequirementJson());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("taskId", context.getTask().getId());
            payload.put("projectId", context.getTask().getProjectId());
            payload.put("templateId", context.getTask().getTemplateId());
            payload.put("templateKey", selection.templateKey());
            payload.put("workspaceDir", context.getWorkspaceDir() == null ? null : context.getWorkspaceDir().toString());
            payload.put("projectTitle", spec.path("title").asText(""));
            payload.put("prd", spec.path("prd").asText(""));
            payload.put("stack", Map.of(
                    "backend", spec.at("/stack/backend").asText("springboot"),
                    "frontend", spec.at("/stack/frontend").asText("vue3"),
                    "db", spec.at("/stack/db").asText("mysql")
            ));
            payload.put("templateFiles", selection.metadata() == null ? Map.of() : selection.metadata().exampleFiles());
            payload.put("jeecg", buildJeecgHints(spec, context, selection));

            String url = buildUrl(jeecgBaseUrl, jeecgRenderPath);
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(Math.max(timeoutMs, 1000));
            conn.setReadTimeout(Math.max(timeoutMs, 1000));
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            byte[] bytes = objectMapper.writeValueAsBytes(payload);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }

            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status < 200 || status >= 300) {
                return new JeecgRenderResult(
                        false,
                        true,
                        "jeecg sidecar returned http status " + status + ", body=" + truncate(body, 300),
                        List.of()
                );
            }

            JsonNode root = body == null || body.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);

            boolean success = root.path("success").asBoolean(false);
            JsonNode filesNode = root.path("files");
            if (filesNode.isMissingNode() || !filesNode.isArray()) {
                filesNode = root.path("data").path("files");
            }
            List<String> files = new ArrayList<>();
            if (filesNode.isArray()) {
                for (JsonNode item : filesNode) {
                    String value = item == null ? null : item.asText(null);
                    if (value != null && !value.isBlank()) {
                        files.add(value.replace('\\', '/'));
                    }
                }
            }

            String message = root.path("message").asText(success ? "ok" : "jeecg render failed");
            return new JeecgRenderResult(success, true, message, List.copyOf(files));
        } catch (Exception e) {
            return new JeecgRenderResult(false, true, "jeecg sidecar call failed: " + e.getMessage(), List.of());
        }
    }

    private String buildUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        String suffix = path == null ? "" : path.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
    }

    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private Map<String, Object> buildJeecgHints(
            JsonNode spec,
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection
    ) {
        Map<String, Object> hints = new LinkedHashMap<>();
        JsonNode jeecgNode = spec == null ? null : spec.path("jeecg");
        if (jeecgNode != null && jeecgNode.isObject()) {
            putIfNotBlank(hints, "id", jeecgNode.path("id").asText(null));
            putIfNotBlank(hints, "formId", jeecgNode.path("formId").asText(null));
            putIfNotBlank(hints, "code", jeecgNode.path("code").asText(null));
            putIfNotBlank(hints, "cgformId", jeecgNode.path("cgformId").asText(null));
            putIfNotBlank(hints, "tableName", jeecgNode.path("tableName").asText(null));
            putIfNotBlank(hints, "projectPath", jeecgNode.path("projectPath").asText(null));
            putIfNotBlank(hints, "packageName", jeecgNode.path("packageName").asText(null));
            putIfNotBlank(hints, "entityPackage", jeecgNode.path("entityPackage").asText(null));
            putIfNotBlank(hints, "bussiPackage", jeecgNode.path("bussiPackage").asText(null));
            putIfNotBlank(hints, "moduleName", jeecgNode.path("moduleName").asText(null));
            putIfNotBlank(hints, "templateStyle", jeecgNode.path("templateStyle").asText(null));
            putIfNotBlank(hints, "stylePath", jeecgNode.path("stylePath").asText(null));
            putIfNotBlank(hints, "vueStyle", jeecgNode.path("vueStyle").asText(null));
            if (jeecgNode.path("request").isObject()) {
                hints.put("request", objectMapper.convertValue(jeecgNode.path("request"), Map.class));
            }
            if (jeecgNode.path("extraParams").isObject()) {
                hints.put("extraParams", objectMapper.convertValue(jeecgNode.path("extraParams"), Map.class));
            }
        }
        if (context != null && context.getTask() != null) {
            putIfNotBlank(hints, "projectPath", context.getWorkspaceDir() == null ? null : context.getWorkspaceDir().toString());
            putIfNotBlank(hints, "code", context.getTask().getTemplateId());
        }
        if (selection != null) {
            putIfNotBlank(hints, "templateStyle", selection.templateKey());
        }
        return hints;
    }

    private void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (target == null || key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        target.putIfAbsent(key, value.trim());
    }

    public record JeecgRenderResult(
            boolean success,
            boolean invoked,
            String message,
            List<String> files
    ) {
    }
}
