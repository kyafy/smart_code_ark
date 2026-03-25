package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class TemplateRepoService {
    private static final Logger logger = LoggerFactory.getLogger(TemplateRepoService.class);

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".json", ".ts", ".tsx", ".js", ".jsx", ".css", ".scss", ".sass", ".less",
            ".vue", ".html", ".yml", ".yaml", ".properties", ".sql", ".java", ".xml", ".env",
            ".example", ".sh", ".ps1", ".gitignore", ".prisma", ".py", ".cfg", ".toml"
    );

    private final ObjectMapper objectMapper;
    private final String configuredRoot;

    public TemplateRepoService(ObjectMapper objectMapper,
                               @Value("${smartark.template-repo.root:template-repo}") String configuredRoot) {
        this.objectMapper = objectMapper;
        this.configuredRoot = configuredRoot == null || configuredRoot.isBlank() ? "template-repo" : configuredRoot.trim();
    }

    public Optional<TemplateSelection> resolveTemplate(String backend, String frontend, String db) {
        return resolveTemplate(null, backend, frontend, db);
    }

    public Optional<TemplateSelection> resolveTemplate(String templateId, String backend, String frontend, String db) {
        Path repoRoot = locateRepoRoot();
        if (repoRoot == null) {
            logger.warn("Template repo root not found, configuredRoot={}", configuredRoot);
            return Optional.empty();
        }
        if (templateId != null && !templateId.isBlank()) {
            return resolveTemplateById(repoRoot, templateId);
        }

        return loadTemplateMetadata(repoRoot).stream()
                .filter(metadata -> scoreTemplate(metadata, backend, frontend, db) >= 0)
                .max(Comparator.comparingInt(metadata -> scoreTemplate(metadata, backend, frontend, db)))
                .flatMap(metadata -> resolveTemplateById(repoRoot, metadata.templateKey()));
    }

    public Optional<TemplateSelection> resolveTemplateById(String templateId) {
        Path repoRoot = locateRepoRoot();
        if (repoRoot == null) {
            logger.warn("Template repo root not found, configuredRoot={}", configuredRoot);
            return Optional.empty();
        }
        return resolveTemplateById(repoRoot, templateId);
    }

    public boolean templateExists(String templateId) {
        return resolveTemplateById(templateId).isPresent();
    }

    public List<String> listTemplateFiles(TemplateSelection selection) {
        try (Stream<Path> stream = Files.walk(selection.templateRoot())) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(selection.templateRoot()::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .filter(path -> !path.equals("template.json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to list template files for {}", selection.templateKey(), e);
            return List.of();
        }
    }

    public void materializeTemplate(AgentExecutionContext context) {
        if (context == null || context.getWorkspaceDir() == null || context.getSpec() == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(context.getSpec().getRequirementJson());
            String backend = root.at("/stack/backend").asText("");
            String frontend = root.at("/stack/frontend").asText("");
            String db = root.at("/stack/db").asText("");
            String explicitTemplateId = context.getTask() == null ? null : context.getTask().getTemplateId();
            Optional<TemplateSelection> selectionOptional = resolveTemplate(explicitTemplateId, backend, frontend, db);
            if (selectionOptional.isEmpty()) {
                return;
            }

            TemplateSelection selection = selectionOptional.get();
            Map<String, String> replacements = buildReplacements(root, context);
            Files.createDirectories(context.getWorkspaceDir());
            for (String relativePath : listTemplateFiles(selection)) {
                Path source = selection.templateRoot().resolve(relativePath);
                Path target = context.getWorkspaceDir().resolve(relativePath).normalize();
                if (!target.startsWith(context.getWorkspaceDir().normalize())) {
                    logger.warn("Skip template file outside workspace: {}", relativePath);
                    continue;
                }
                if (Files.exists(target)) {
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                if (isTextFile(relativePath)) {
                    String content = Files.readString(source, StandardCharsets.UTF_8);
                    for (Map.Entry<String, String> entry : replacements.entrySet()) {
                        content = content.replace(entry.getKey(), entry.getValue());
                    }
                    Files.writeString(target, content, StandardCharsets.UTF_8);
                } else {
                    Files.copy(source, target);
                }
            }
            logger.info("Template materialized: templateKey={}, workspace={}", selection.templateKey(), context.getWorkspaceDir());
            context.logInfo("Template materialized: " + selection.templateKey());
        } catch (Exception e) {
            logger.warn("Failed to materialize template into workspace", e);
            context.logWarn("Template materialize skipped: " + e.getMessage());
        }
    }

    public boolean isTemplateManaged(String reason) {
        return reason != null && reason.startsWith("template_repo:");
    }

    /**
     * Read a single template file's content by template selection + relative path.
     */
    public String readTemplateFileContent(TemplateSelection selection, String relativePath) {
        if (selection == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path filePath = selection.templateRoot().resolve(relativePath).normalize();
        if (!filePath.startsWith(selection.templateRoot().normalize())) {
            return null;
        }
        if (!Files.isRegularFile(filePath)) {
            return null;
        }
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warn("Failed to read template file: {}", relativePath, e);
            return null;
        }
    }

    /**
     * Resolve example code context for a target file path based on file type pattern matching.
     * Uses exampleFiles from template.json metadata when available, falls back to convention-based matching.
     */
    public ExampleContext resolveExampleContext(TemplateSelection selection, String targetFilePath) {
        if (selection == null || targetFilePath == null || targetFilePath.isBlank()) {
            return ExampleContext.EMPTY;
        }
        String lower = targetFilePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        Map<String, String> examples = selection.metadata().exampleFiles();

        // Backend Java files
        if (lower.endsWith("controller.java")) {
            return buildExampleFromKeys(selection, examples,
                    "backendController",
                    "backendService", "backendEntity", "backendRequest");
        }
        if (lower.endsWith("service.java") && !lower.contains("repository")) {
            return buildExampleFromKeys(selection, examples,
                    "backendService",
                    "backendRepository", "backendEntity", "backendRequest");
        }
        if (lower.endsWith("repository.java")) {
            return buildExampleFromKeys(selection, examples,
                    "backendRepository",
                    "backendEntity");
        }
        if (lower.endsWith("entity.java")) {
            return buildExampleFromKeys(selection, examples,
                    "backendEntity");
        }
        if (lower.endsWith("request.java") || lower.endsWith("dto.java")) {
            return buildExampleFromKeys(selection, examples,
                    "backendRequest",
                    "backendEntity");
        }

        // Frontend Vue/TS files
        if (lower.endsWith(".vue")) {
            return buildExampleFromKeys(selection, examples,
                    "frontendPage",
                    "frontendApi");
        }
        if (lower.endsWith(".ts") && (lower.contains("api") || lower.contains("service") || lower.contains("client"))) {
            return buildExampleFromKeys(selection, examples,
                    "frontendApi");
        }
        if (lower.endsWith(".ts") && (lower.contains("store") || lower.contains("pinia"))) {
            return buildExampleFromKeys(selection, examples,
                    "frontendApi",
                    "frontendPage");
        }

        // Python backend files (FastAPI / Django)
        if (lower.endsWith("views.py") || lower.endsWith("router.py")
                || (lower.endsWith("main.py") && lower.contains("app/"))) {
            return buildExampleFromKeys(selection, examples,
                    "backendController",
                    "backendEntity", "backendRequest");
        }
        if (lower.endsWith("models.py") && !lower.contains("migration")) {
            return buildExampleFromKeys(selection, examples,
                    "backendEntity");
        }
        if (lower.endsWith("schemas.py") || lower.endsWith("serializers.py")) {
            return buildExampleFromKeys(selection, examples,
                    "backendRequest",
                    "backendEntity");
        }
        if (lower.endsWith("service.py") || lower.endsWith("services.py") || lower.endsWith("crud.py")) {
            return buildExampleFromKeys(selection, examples,
                    "backendService",
                    "backendEntity", "backendRequest");
        }

        // Next.js / React files
        if (lower.endsWith(".tsx") && lower.contains("/page")) {
            return buildExampleFromKeys(selection, examples,
                    "frontendPage",
                    "frontendApi", "frontendComponent");
        }
        if (lower.endsWith(".tsx") && lower.contains("/component")) {
            return buildExampleFromKeys(selection, examples,
                    "frontendComponent",
                    "frontendApi");
        }
        if (lower.endsWith("route.ts") && lower.contains("/api/")) {
            return buildExampleFromKeys(selection, examples,
                    "backendController",
                    "frontendApi");
        }

        // SQL migration files
        if (lower.endsWith(".sql")) {
            return buildExampleFromKeys(selection, examples,
                    "dbMigration");
        }
        // Prisma schema
        if (lower.endsWith(".prisma")) {
            return buildExampleFromKeys(selection, examples,
                    "dbMigration");
        }

        return ExampleContext.EMPTY;
    }

    /**
     * Resolve example context specifically for test file generation.
     * Returns test example + source file content as related context.
     */
    public ExampleContext resolveTestExampleContext(TemplateSelection selection, String targetTestFilePath, String sourceFileContent) {
        if (selection == null || targetTestFilePath == null || targetTestFilePath.isBlank()) {
            return ExampleContext.EMPTY;
        }
        String lower = targetTestFilePath.toLowerCase(Locale.ROOT).replace('\\', '/');
        Map<String, String> examples = selection.metadata().exampleFiles();

        String testKey = null;
        if (lower.endsWith("controllertest.java") || lower.endsWith("controller_test.java")) {
            testKey = "backendControllerTest";
        } else if (lower.endsWith("servicetest.java") || lower.endsWith("service_test.java")) {
            testKey = "backendServiceTest";
        } else if (lower.endsWith(".test.ts") || lower.endsWith(".spec.ts")
                || lower.endsWith(".test.tsx") || lower.endsWith(".spec.tsx")) {
            testKey = "frontendPageTest";
        } else if (lower.matches(".*test_[a-z_]+\\.py$") || lower.matches(".*tests\\.py$")) {
            // Python test files: test_main.py, test_views.py, tests.py
            testKey = "backendServiceTest";
        }

        if (testKey == null || !examples.containsKey(testKey)) {
            return ExampleContext.EMPTY;
        }

        String primary = readTemplateFileContent(selection, examples.get(testKey));
        String related = sourceFileContent != null
                ? "// --- 待测试的源文件内容 ---\n" + sourceFileContent
                : null;
        return new ExampleContext(primary, related);
    }

    private ExampleContext buildExampleFromKeys(TemplateSelection selection, Map<String, String> examples,
                                                String primaryKey, String... relatedKeys) {
        if (examples == null || examples.isEmpty() || !examples.containsKey(primaryKey)) {
            return ExampleContext.EMPTY;
        }
        String primary = readTemplateFileContent(selection, examples.get(primaryKey));
        if (primary == null) {
            return ExampleContext.EMPTY;
        }
        StringBuilder related = new StringBuilder();
        for (String key : relatedKeys) {
            String path = examples.get(key);
            if (path != null) {
                String content = readTemplateFileContent(selection, path);
                if (content != null) {
                    related.append("// --- ").append(key).append(" ---\n").append(content).append("\n\n");
                }
            }
        }
        return new ExampleContext(primary, related.length() > 0 ? related.toString() : null);
    }

    public record ExampleContext(String primaryExample, String relatedExamples) {
        public static final ExampleContext EMPTY = new ExampleContext(null, null);

        public boolean hasExample() {
            return primaryExample != null && !primaryExample.isBlank();
        }
    }

    private Optional<TemplateSelection> resolveTemplateById(Path repoRoot, String templateId) {
        String normalizedTemplateId = normalizeTemplateId(templateId);
        if (normalizedTemplateId == null) {
            return Optional.empty();
        }
        Path templateRoot = repoRoot.resolve("templates").resolve(normalizedTemplateId);
        if (!Files.isDirectory(templateRoot)) {
            logger.warn("Template directory not found: {}", templateRoot);
            return Optional.empty();
        }
        TemplateMetadata metadata = loadMergedTemplateMetadata(repoRoot, templateRoot, normalizedTemplateId);
        return Optional.of(new TemplateSelection(
                metadata.templateKey(),
                templateRoot,
                resolveBackendRoot(metadata),
                resolveFrontendRoot(metadata),
                metadata
        ));
    }

    private List<TemplateMetadata> loadTemplateMetadata(Path repoRoot) {
        List<CatalogEntry> catalogEntries = loadCatalogEntries(repoRoot);
        Set<String> templateKeys = new LinkedHashSet<>();
        catalogEntries.stream().map(CatalogEntry::templateKey).forEach(templateKeys::add);
        templateKeys.addAll(scanTemplateKeys(repoRoot));

        List<TemplateMetadata> result = new ArrayList<>();
        for (String templateKey : templateKeys) {
            Path templateRoot = repoRoot.resolve("templates").resolve(templateKey);
            if (!Files.isDirectory(templateRoot)) {
                continue;
            }
            result.add(loadMergedTemplateMetadata(repoRoot, templateRoot, templateKey));
        }
        return result;
    }

    private List<String> scanTemplateKeys(Path repoRoot) {
        Path templatesRoot = repoRoot.resolve("templates");
        if (!Files.isDirectory(templatesRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(templatesRoot)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            logger.warn("Failed to scan template roots under {}", templatesRoot, e);
            return List.of();
        }
    }

    private TemplateMetadata loadMergedTemplateMetadata(Path repoRoot, Path templateRoot, String templateKey) {
        CatalogEntry catalogEntry = findCatalogEntry(repoRoot, templateKey).orElse(null);
        TemplateManifest templateManifest = loadTemplateManifest(templateRoot).orElse(null);

        String resolvedKey = firstNonBlank(
                templateManifest == null ? null : templateManifest.templateKey(),
                catalogEntry == null ? null : catalogEntry.templateKey(),
                templateKey
        );
        String name = firstNonBlank(
                templateManifest == null ? null : templateManifest.name(),
                catalogEntry == null ? null : catalogEntry.name(),
                resolvedKey
        );
        String category = catalogEntry == null ? null : catalogEntry.category();
        String description = catalogEntry == null ? null : catalogEntry.description();
        Map<String, String> paths = catalogEntry != null && !catalogEntry.paths().isEmpty()
                ? catalogEntry.paths()
                : inferPaths(templateRoot);
        Map<String, String> stack = templateManifest == null ? Map.of() : templateManifest.stack();
        Map<String, String> run = templateManifest == null ? Map.of() : templateManifest.run();
        Map<String, String> exampleFiles = templateManifest == null ? Map.of() : templateManifest.exampleFiles();

        return new TemplateMetadata(
                resolvedKey,
                name,
                category,
                description,
                Map.copyOf(paths),
                Map.copyOf(stack),
                Map.copyOf(run),
                exampleFiles == null ? Map.of() : Map.copyOf(exampleFiles)
        );
    }

    private Optional<CatalogEntry> findCatalogEntry(Path repoRoot, String templateKey) {
        return loadCatalogEntries(repoRoot).stream()
                .filter(entry -> templateKey.equals(entry.templateKey()))
                .findFirst();
    }

    private List<CatalogEntry> loadCatalogEntries(Path repoRoot) {
        Path catalogPath = repoRoot.resolve("catalog.json");
        if (!Files.exists(catalogPath)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(catalogPath, StandardCharsets.UTF_8));
            JsonNode templatesNode = root.path("templates");
            if (!templatesNode.isArray()) {
                return List.of();
            }
            List<CatalogEntry> entries = new ArrayList<>();
            for (JsonNode item : templatesNode) {
                String templateKey = textOrNull(item, "key");
                if (templateKey == null) {
                    continue;
                }
                entries.add(new CatalogEntry(
                        templateKey,
                        textOrNull(item, "name"),
                        textOrNull(item, "category"),
                        textOrNull(item, "description"),
                        readStringMap(item.path("paths"))
                ));
            }
            return entries;
        } catch (Exception e) {
            logger.warn("Failed to parse template catalog at {}", catalogPath, e);
            return List.of();
        }
    }

    private Optional<TemplateManifest> loadTemplateManifest(Path templateRoot) {
        Path manifestPath = templateRoot.resolve("template.json");
        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(manifestPath, StandardCharsets.UTF_8));
            return Optional.of(new TemplateManifest(
                    textOrNull(root, "key"),
                    textOrNull(root, "name"),
                    readStringMap(root.path("stack")),
                    readStringMap(root.path("run")),
                    readStringMap(root.path("exampleFiles"))
            ));
        } catch (Exception e) {
            logger.warn("Failed to parse template manifest at {}", manifestPath, e);
            return Optional.empty();
        }
    }

    private Map<String, String> inferPaths(Path templateRoot) {
        Map<String, String> inferred = new LinkedHashMap<>();
        if (Files.isDirectory(templateRoot.resolve("backend"))) {
            inferred.put("backend", "backend");
        }
        if (Files.isDirectory(templateRoot.resolve("frontend"))) {
            inferred.put("frontend", "frontend");
        }
        if (Files.isDirectory(templateRoot.resolve("frontend-mobile"))) {
            inferred.put("frontend", "frontend-mobile");
        }
        if (Files.exists(templateRoot.resolve("package.json")) || Files.isDirectory(templateRoot.resolve("app"))) {
            inferred.putIfAbsent("app", ".");
        }
        return inferred;
    }

    private boolean matchesStack(TemplateMetadata metadata, String backend, String frontend, String db) {
        String haystack = buildSearchText(metadata);
        return matchesValue(haystack, backend, StackDimension.BACKEND)
                && matchesValue(haystack, frontend, StackDimension.FRONTEND)
                && matchesValue(haystack, db, StackDimension.DB);
    }

    private int scoreTemplate(TemplateMetadata metadata, String backend, String frontend, String db) {
        String haystack = buildSearchText(metadata);
        int backendScore = scoreValue(haystack, backend, StackDimension.BACKEND);
        int frontendScore = scoreValue(haystack, frontend, StackDimension.FRONTEND);
        int dbScore = scoreValue(haystack, db, StackDimension.DB);
        if (backendScore < 0 || frontendScore < 0 || dbScore < 0) {
            return -1;
        }
        return backendScore + frontendScore + dbScore;
    }

    private String buildSearchText(TemplateMetadata metadata) {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, metadata.templateKey());
        addIfPresent(parts, metadata.name());
        addIfPresent(parts, metadata.category());
        addIfPresent(parts, metadata.description());
        parts.addAll(metadata.stack().values());
        parts.addAll(metadata.paths().values());
        return normalizeSearchText(String.join(" ", parts));
    }

    private boolean matchesValue(String haystack, String requestedValue, StackDimension dimension) {
        if (requestedValue == null || requestedValue.isBlank()) {
            return true;
        }
        for (String alias : aliasesFor(requestedValue, dimension)) {
            if (containsNormalized(haystack, alias)) {
                return true;
            }
        }
        return false;
    }

    private int scoreValue(String haystack, String requestedValue, StackDimension dimension) {
        if (requestedValue == null || requestedValue.isBlank()) {
            return 0;
        }
        List<String> aliases = aliasesFor(requestedValue, dimension);
        for (int i = 0; i < aliases.size(); i++) {
            if (containsNormalized(haystack, aliases.get(i))) {
                return 100 - i;
            }
        }
        return -1;
    }

    private List<String> aliasesFor(String value, StackDimension dimension) {
        String normalized = normalize(value);
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(normalized);
        switch (dimension) {
            case BACKEND -> {
                if (normalized.contains("spring")) {
                    aliases.add("spring boot");
                    aliases.add("springboot");
                    aliases.add("java");
                }
                if (normalized.contains("java")) {
                    aliases.add("spring");
                }
                if (normalized.contains("fastapi")) {
                    aliases.add("fast api");
                    aliases.add("python");
                }
                if (normalized.contains("python")) {
                    aliases.add("fastapi");
                    aliases.add("fast api");
                    aliases.add("django");
                }
                if (normalized.contains("django")) {
                    aliases.add("python");
                }
                if (normalized.contains("next")) {
                    aliases.add("next");
                    aliases.add("next.js");
                }
            }
            case FRONTEND -> {
                if (normalized.contains("vue")) {
                    aliases.add("vue");
                    aliases.add("vue 3");
                    aliases.add("vite");
                }
                if (normalized.contains("react")) {
                    aliases.add("react");
                }
                if (normalized.contains("next")) {
                    aliases.add("next");
                    aliases.add("next.js");
                    aliases.add("react");
                }
                if (normalized.contains("uni")) {
                    aliases.add("uniapp");
                    aliases.add("uni-app");
                    aliases.add("mobile");
                    aliases.add("h5");
                }
            }
            case DB -> {
                if (normalized.contains("mysql")) {
                    aliases.add("mysql");
                }
                if (normalized.contains("postgres")) {
                    aliases.add("postgres");
                    aliases.add("postgresql");
                }
            }
        }
        return aliases.stream().filter(alias -> alias != null && !alias.isBlank()).toList();
    }

    private boolean containsNormalized(String haystack, String needle) {
        String normalizedNeedle = normalizeSearchText(needle);
        if (normalizedNeedle.isBlank()) {
            return false;
        }
        String compactHaystack = haystack.replace(" ", "");
        String compactNeedle = normalizedNeedle.replace(" ", "");
        return haystack.contains(normalizedNeedle) || compactHaystack.contains(compactNeedle);
    }

    private String resolveBackendRoot(TemplateMetadata metadata) {
        if (metadata.paths().containsKey("backend")) {
            return metadata.paths().get("backend");
        }
        if (metadata.paths().containsKey("app")) {
            return metadata.paths().get("app");
        }
        return "backend";
    }

    private String resolveFrontendRoot(TemplateMetadata metadata) {
        if (metadata.paths().containsKey("frontend")) {
            return metadata.paths().get("frontend");
        }
        if (metadata.paths().containsKey("app")) {
            return metadata.paths().get("app");
        }
        return "frontend";
    }

    private Map<String, String> buildReplacements(JsonNode root, AgentExecutionContext context) {
        String rawDisplayName = root.path("title").asText("");
        if (rawDisplayName == null || rawDisplayName.isBlank()) {
            rawDisplayName = context.getTask() != null && context.getTask().getProjectId() != null
                    ? context.getTask().getProjectId()
                    : "Smart Template App";
        }

        String projectName = slugify(rawDisplayName);
        if (projectName.isBlank()) {
            if (context.getTask() != null && context.getTask().getProjectId() != null && !context.getTask().getProjectId().isBlank()) {
                projectName = context.getTask().getProjectId().toLowerCase(Locale.ROOT);
            } else {
                projectName = "smart-template-app";
            }
        }

        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("__PROJECT_NAME__", projectName);
        replacements.put("__DISPLAY_NAME__", rawDisplayName.trim());
        return replacements;
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            String value = entry.getValue() == null ? null : entry.getValue().asText(null);
            if (value != null && !value.isBlank()) {
                values.put(entry.getKey(), value.trim());
            }
        });
        return values;
    }

    private void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeTemplateId(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        return templateId.trim();
    }

    private String normalizeSearchText(String value) {
        return normalize(value)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isTextFile(String relativePath) {
        String path = relativePath == null ? "" : relativePath.toLowerCase(Locale.ROOT);
        if (path.endsWith(".env.example") || path.endsWith(".gitignore")) {
            return true;
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(path.substring(lastDot));
    }

    private Path locateRepoRoot() {
        Path configuredPath = Paths.get(configuredRoot);
        if (configuredPath.isAbsolute()) {
            if (Files.exists(configuredPath.resolve("catalog.json"))) {
                return configuredPath.normalize();
            }
            return null;
        }

        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(configuredRoot);
            if (Files.exists(candidate.resolve("catalog.json"))) {
                return candidate.normalize();
            }
            current = current.getParent();
        }
        return null;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.path(fieldName).asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record TemplateSelection(
            String templateKey,
            Path templateRoot,
            String backendRoot,
            String frontendRoot,
            TemplateMetadata metadata
    ) {
    }

    public record TemplateMetadata(
            String templateKey,
            String name,
            String category,
            String description,
            Map<String, String> paths,
            Map<String, String> stack,
            Map<String, String> run,
            Map<String, String> exampleFiles
    ) {
    }

    private record CatalogEntry(
            String templateKey,
            String name,
            String category,
            String description,
            Map<String, String> paths
    ) {
    }

    private record TemplateManifest(
            String templateKey,
            String name,
            Map<String, String> stack,
            Map<String, String> run,
            Map<String, String> exampleFiles
    ) {
    }

    private enum StackDimension {
        BACKEND,
        FRONTEND,
        DB
    }
}
