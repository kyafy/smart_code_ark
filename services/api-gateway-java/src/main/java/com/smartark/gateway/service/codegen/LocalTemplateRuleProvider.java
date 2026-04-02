package com.smartark.gateway.service.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalTemplateRuleProvider implements CodegenProvider {
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_.]+)}");
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".md", ".json", ".ts", ".tsx", ".js", ".jsx", ".css", ".scss", ".sass", ".less",
            ".vue", ".html", ".yml", ".yaml", ".properties", ".sql", ".java", ".xml", ".env",
            ".example", ".sh", ".ps1", ".gitignore", ".prisma", ".py", ".cfg", ".toml", ".ftl",
            ".javai", ".vuei", ".tsi", ".jsi", ".pyi"
    );
    private static final Map<String, String> EXTENSION_REWRITE = Map.of(
            ".javai", ".java",
            ".vuei", ".vue",
            ".tsi", ".ts",
            ".jsi", ".js",
            ".pyi", ".py"
    );

    private final ObjectMapper objectMapper;

    @Value("${smartark.codegen.local-template.enabled:true}")
    private boolean localTemplateEnabled;

    @Value("${smartark.codegen.local-template.path-rewrite-enabled:true}")
    private boolean pathRewriteEnabled;

    @Value("${smartark.codegen.local-template.content-rewrite-enabled:true}")
    private boolean contentRewriteEnabled;

    @Value("${smartark.codegen.local-template.extension-rewrite-enabled:true}")
    private boolean extensionRewriteEnabled;

    @Value("${smartark.codegen.local-template.overwrite-target:true}")
    private boolean overwriteTarget;

    public LocalTemplateRuleProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerKey() {
        return "local_template";
    }

    @Override
    public CodegenRenderResult tryRender(
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection,
            String codegenEngine
    ) {
        if (!localTemplateEnabled) {
            return CodegenRenderResult.notInvoked(providerKey(), "local template provider is disabled");
        }
        if (context == null || context.getWorkspaceDir() == null || selection == null) {
            return CodegenRenderResult.notInvoked(providerKey(), "invalid context or template selection");
        }
        Path workspaceDir = context.getWorkspaceDir().normalize();
        if (!Files.isDirectory(workspaceDir)) {
            return CodegenRenderResult.failed(providerKey(), "workspace directory not found: " + workspaceDir);
        }

        try {
            RenderVariables renderVariables = buildRenderVariables(context);
            RewriteStats stats = rewriteWorkspace(workspaceDir, renderVariables);
            List<String> files = listWorkspaceFiles(workspaceDir);
            if (files.isEmpty()) {
                return CodegenRenderResult.failed(providerKey(), "local template provider produced no files");
            }
            String message = "local template rewrite done: renamed=" + stats.renamedFiles()
                    + ", rewritten=" + stats.rewrittenFiles()
                    + ", conflicts=" + stats.conflicts();
            return new CodegenRenderResult(true, true, providerKey(), message, files);
        } catch (Exception e) {
            return CodegenRenderResult.failed(providerKey(), "local template rewrite failed: " + e.getMessage());
        }
    }

    private RenderVariables buildRenderVariables(AgentExecutionContext context) {
        Map<String, String> contentVars = new LinkedHashMap<>();
        contentVars.put("projectName", "smartark-project");
        contentVars.put("displayName", "SmartArk Project");
        contentVars.put("moduleName", "demo");
        contentVars.put("entityName", "DemoEntity");
        contentVars.put("tableName", "smartark_demo");
        contentVars.put("bussiPackage", "com.smartark.generated");
        contentVars.put("packageName", "com.smartark.generated");
        contentVars.put("entityPackage", "demo");
        contentVars.put("entityPackagePath", "demo");

        try {
            JsonNode specNode = context.getSpec() == null
                    ? null
                    : objectMapper.readTree(context.getSpec().getRequirementJson());
            JsonNode jeecgNode = specNode == null ? null : specNode.path("jeecg");

            String projectName = firstNonBlank(
                    safeText(specNode, "title"),
                    context.getTask() == null ? null : context.getTask().getProjectId(),
                    "smartark-project"
            );
            projectName = slugify(projectName);
            String displayName = firstNonBlank(safeText(specNode, "title"), projectName);
            String moduleName = firstNonBlank(
                    safeText(jeecgNode, "moduleName"),
                    safeText(specNode, "projectType"),
                    "demo"
            );
            moduleName = slugify(moduleName).replace('-', '_');
            if (moduleName.isBlank()) {
                moduleName = "demo";
            }

            String bussiPackage = firstNonBlank(
                    safeText(jeecgNode, "bussiPackage"),
                    safeText(jeecgNode, "packageName"),
                    "com.smartark.generated"
            );
            String entityPackage = firstNonBlank(
                    safeText(jeecgNode, "entityPackage"),
                    "demo"
            );
            String entityName = firstNonBlank(
                    safeText(jeecgNode, "entityName"),
                    toPascalCase(moduleName) + "Entity"
            );
            String tableName = firstNonBlank(
                    safeText(jeecgNode, "tableName"),
                    toSnakeCase(entityName)
            );

            contentVars.put("projectName", projectName);
            contentVars.put("displayName", displayName);
            contentVars.put("moduleName", moduleName);
            contentVars.put("entityName", entityName);
            contentVars.put("tableName", tableName);
            contentVars.put("bussiPackage", bussiPackage);
            contentVars.put("packageName", bussiPackage);
            contentVars.put("entityPackage", entityPackage);
            contentVars.put("entityPackagePath", entityPackage.replace('.', '/'));
        } catch (Exception ignored) {
            // keep defaults
        }

        Map<String, String> pathVars = new LinkedHashMap<>(contentVars);
        pathVars.put("bussiPackage", contentVars.getOrDefault("bussiPackage", "").replace('.', '/'));
        pathVars.put("packageName", contentVars.getOrDefault("packageName", "").replace('.', '/'));
        pathVars.put("entityPackage", contentVars.getOrDefault("entityPackage", "").replace('.', '/'));
        pathVars.put("entityPackagePath", contentVars.getOrDefault("entityPackagePath", "").replace('.', '/'));
        return new RenderVariables(pathVars, contentVars);
    }

    private RewriteStats rewriteWorkspace(Path workspaceDir, RenderVariables variables) throws IOException {
        List<Path> files = Files.walk(workspaceDir)
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        int renamed = 0;
        int rewritten = 0;
        int conflicts = 0;

        for (Path file : files) {
            Path relative = workspaceDir.relativize(file);
            String relativePosix = toPosix(relative);
            String targetRelativePosix = relativePosix;

            if (pathRewriteEnabled) {
                targetRelativePosix = replacePlaceholders(relativePosix, variables.pathVars());
                targetRelativePosix = normalizeRelativePath(targetRelativePosix);
            }
            if (extensionRewriteEnabled) {
                targetRelativePosix = rewriteTemplateSuffix(targetRelativePosix);
            }

            Path effectiveFile = file;
            if (!targetRelativePosix.equals(relativePosix)) {
                Path target = workspaceDir.resolve(targetRelativePosix).normalize();
                if (!target.startsWith(workspaceDir)) {
                    continue;
                }
                if (Files.exists(target) && !overwriteTarget) {
                    conflicts++;
                } else {
                    if (target.getParent() != null) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    effectiveFile = target;
                    renamed++;
                }
            }

            if (contentRewriteEnabled && isTextFile(effectiveFile)) {
                String original = Files.readString(effectiveFile, StandardCharsets.UTF_8);
                String replaced = replacePlaceholders(original, variables.contentVars());
                if (!replaced.equals(original)) {
                    Files.writeString(effectiveFile, replaced, StandardCharsets.UTF_8);
                    rewritten++;
                }
            }
        }
        return new RewriteStats(renamed, rewritten, conflicts);
    }

    private List<String> listWorkspaceFiles(Path workspaceDir) throws IOException {
        return Files.walk(workspaceDir)
                .filter(Files::isRegularFile)
                .map(path -> workspaceDir.relativize(path))
                .map(this::toPosix)
                .sorted()
                .toList();
    }

    private String replacePlaceholders(String input, Map<String, String> variables) {
        if (input == null || input.isBlank() || variables == null || variables.isEmpty()) {
            return input;
        }
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.get(key);
            if (value == null) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
            }
        }
        matcher.appendTail(buffer);
        String output = buffer.toString();
        output = output.replace("__PROJECT_NAME__", variables.getOrDefault("projectName", "smartark-project"));
        output = output.replace("__DISPLAY_NAME__", variables.getOrDefault("displayName", "SmartArk Project"));
        return output;
    }

    private String rewriteTemplateSuffix(String relativePath) {
        String lower = relativePath.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : EXTENSION_REWRITE.entrySet()) {
            if (lower.endsWith(entry.getKey())) {
                return relativePath.substring(0, relativePath.length() - entry.getKey().length()) + entry.getValue();
            }
        }
        return relativePath;
    }

    private boolean isTextFile(Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals(".gitignore") || name.equals(".env.example")) {
            return true;
        }
        for (String suffix : TEXT_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRelativePath(String pathValue) {
        String normalized = pathValue == null ? "" : pathValue.replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("../")) {
            normalized = normalized.replace("../", "");
        }
        return normalized;
    }

    private String toPosix(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String safeText(JsonNode node, String field) {
        if (node == null || field == null || field.isBlank()) {
            return null;
        }
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "smartark-project";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "smartark-project" : normalized;
    }

    private String toPascalCase(String value) {
        if (value == null || value.isBlank()) {
            return "Demo";
        }
        String[] parts = value.replace('-', '_').split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        String output = builder.toString();
        return output.isBlank() ? "Demo" : output;
    }

    private String toSnakeCase(String value) {
        if (value == null || value.isBlank()) {
            return "smartark_demo";
        }
        String normalized = value
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .toLowerCase(Locale.ROOT)
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "smartark_demo" : normalized;
    }

    private record RenderVariables(Map<String, String> pathVars, Map<String, String> contentVars) {
    }

    private record RewriteStats(int renamedFiles, int rewrittenFiles, int conflicts) {
    }
}
