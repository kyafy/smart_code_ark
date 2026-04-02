package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.StepMemoryService;
import com.smartark.gateway.service.TemplateRepoService;
import com.smartark.gateway.service.codegen.CodegenRenderResult;
import com.smartark.gateway.service.codegen.InternalCodegenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class RequirementAnalyzeStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(RequirementAnalyzeStep.class);

    private final ModelService modelService;
    private final ObjectMapper objectMapper;
    private final StepMemoryService stepMemoryService;
    private final TemplateRepoService templateRepoService;
    @Autowired(required = false)
    private InternalCodegenService internalCodegenService;

    public RequirementAnalyzeStep(ModelService modelService,
                                  ObjectMapper objectMapper,
                                  StepMemoryService stepMemoryService,
                                  TemplateRepoService templateRepoService) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
        this.stepMemoryService = stepMemoryService;
        this.templateRepoService = templateRepoService;
    }

    @Override
    public String getStepCode() {
        return "requirement_analyze";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Analyzing requirements and planning project structure: taskId={}", context.getTask().getId());
        context.logInfo("Step requirement_analyze start: taskId=" + context.getTask().getId());

        JsonNode reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
        String prd = reqJson.path("prd").asText("");
        String projectType = reqJson.path("projectType").asText("");
        String stackBackend = reqJson.at("/stack/backend").asText("springboot");
        String stackFrontend = reqJson.at("/stack/frontend").asText("vue3");
        String stackDb = reqJson.at("/stack/db").asText("mysql");
        String explicitTemplateId = context.getTask() == null ? null : context.getTask().getTemplateId();
        String codegenEngine = normalizeCodegenEngine(context.getTask() == null ? null : context.getTask().getCodegenEngine());
        boolean internalOnly = "jeecg_rule".equals(codegenEngine) || "internal_service".equals(codegenEngine);
        boolean internalPreferred = internalOnly || "hybrid".equals(codegenEngine);
        Optional<TemplateRepoService.TemplateSelection> templateSelection = templateRepoService.resolveTemplate(
                explicitTemplateId,
                stackBackend,
                stackFrontend,
                stackDb
        );
        List<String> templateFiles = templateSelection.map(templateRepoService::listTemplateFiles).orElse(List.of());
        String backendRoot = templateSelection.map(TemplateRepoService.TemplateSelection::backendRoot).orElse("backend");
        String frontendRoot = templateSelection.map(TemplateRepoService.TemplateSelection::frontendRoot).orElse("frontend");
        templateSelection.ifPresent(selection -> {
                context.setTemplateKey(selection.templateKey());
                context.setTemplateSelection(selection);
                context.logInfo("Template selected: key=" + selection.templateKey()
                        + ", explicit=" + (explicitTemplateId != null && !explicitTemplateId.isBlank())
                        + ", fileCount=" + templateFiles.size());
        });

        String effectiveInstructions = context.getNormalizedInstructions() != null
                ? context.getNormalizedInstructions()
                : context.getInstructions();

        List<String> internalGeneratedFiles = List.of();
        if (internalPreferred && templateSelection.isPresent()) {
            if (internalCodegenService == null) {
                String message = "internal codegen service is not configured";
                if (internalOnly) {
                    throw new BusinessException(ErrorCodes.TASK_FAILED, message);
                }
                context.logWarn(message + ", fallback to llm");
            } else {
                templateRepoService.materializeTemplate(context);
                CodegenRenderResult renderResult = internalCodegenService.tryRender(codegenEngine, context, templateSelection.get());
                context.logInfo("Internal codegen invoked=" + renderResult.invoked()
                        + ", provider=" + renderResult.provider()
                        + ", success=" + renderResult.success()
                        + ", files=" + renderResult.files().size()
                        + ", message=" + renderResult.message());
                if (renderResult.success() && !renderResult.files().isEmpty()) {
                    internalGeneratedFiles = renderResult.files();
                } else if (internalOnly) {
                    throw new BusinessException(ErrorCodes.TASK_FAILED, "internal codegen render failed: " + renderResult.message());
                }
            }
        }

        List<String> generatedFiles;
        if (!internalGeneratedFiles.isEmpty()) {
            generatedFiles = internalGeneratedFiles;
        } else if (internalOnly) {
            generatedFiles = templateFiles;
        } else {
            try {
                generatedFiles = modelService.generateProjectStructure(
                        context.getTask().getId(),
                        context.getTask().getProjectId(),
                        prd, stackBackend, stackFrontend, stackDb, effectiveInstructions
                );
            } catch (Exception e) {
                logger.warn("Project structure generation failed, fallback to builtin structure", e);
                generatedFiles = templateSelection.isPresent()
                        ? List.of()
                        : fallbackStructure(prd, projectType, stackBackend, stackFrontend, stackDb);
            }
        }
        List<String> fileList = sanitizeFileList(
                generatedFiles,
                prd,
                projectType,
                stackBackend,
                stackFrontend,
                stackDb,
                templateSelection.isEmpty(),
                backendRoot,
                frontendRoot
        );
        fileList = mergeTemplateFiles(templateFiles, fileList);
        context.logInfo("Structure after sanitize: fileCount=" + fileList.size());
        StructureCompleteness completeness = validateStructureCompleteness(fileList, stackBackend, stackFrontend, stackDb, backendRoot, frontendRoot);
        if (completeness.passed()) {
            context.logInfo("Structure completeness check PASSED: all critical files present");
        }
        if (!completeness.passed() && !completeness.missingFiles().isEmpty()) {
            context.logWarn("Structure completeness check FAILED: missing=" + completeness.missingFiles());
            String retryInstruction = (effectiveInstructions == null ? "" : effectiveInstructions) +
                    "\n\n请补齐以下缺失关键文件：\n" + String.join("\n", completeness.missingFiles());
            try {
                logger.warn("Project structure missing critical files, corrective retry: taskId={}, missing={}",
                        context.getTask().getId(), completeness.missingFiles());
                List<String> retried = modelService.generateProjectStructure(
                        context.getTask().getId(),
                        context.getTask().getProjectId(),
                        prd, stackBackend, stackFrontend, stackDb, retryInstruction
                );
                fileList = sanitizeFileList(
                        retried,
                        prd,
                        projectType,
                        stackBackend,
                        stackFrontend,
                        stackDb,
                        templateSelection.isEmpty(),
                        backendRoot,
                        frontendRoot
                );
                fileList = mergeTemplateFiles(templateFiles, fileList);
                context.logInfo("Corrective retry result: fileCount=" + fileList.size());
            } catch (Exception e) {
                logger.warn("Corrective retry for project structure failed", e);
                context.logWarn("Corrective retry failed: " + e.getMessage());
            }
        }

        List<FilePlanItem> filePlan = buildFilePlan(fileList, templateFiles, templateSelection.map(TemplateRepoService.TemplateSelection::templateKey).orElse(null));
        enforceFrontendBusinessCoverage(prd, stackFrontend, frontendRoot, filePlan, context);
        enforceBackendBusinessCoverage(prd, stackBackend, backendRoot, filePlan, context);

        context.setFileList(filePlan.stream().map(FilePlanItem::getPath).toList());
        context.setFilePlan(filePlan);
        logger.info("Generated {} files in plan.", fileList.size());
        context.logInfo("Step requirement_analyze output: filePlanSize=" + filePlan.size()
                + ", groups=" + filePlan.stream().map(FilePlanItem::getGroup).distinct().toList());
        if (templateSelection.isPresent() && internalGeneratedFiles.isEmpty()) {
            templateRepoService.materializeTemplate(context);
        }

        // Persist filePlan and templateKey to step memory for recovery on retry
        String taskId = context.getTask().getId();
        stepMemoryService.save(taskId, "requirement_analyze", "filePlan", filePlan);
        stepMemoryService.save(taskId, "requirement_analyze", "fileList", context.getFileList());
        if (context.getTemplateKey() != null) {
            stepMemoryService.save(taskId, "requirement_analyze", "templateKey", context.getTemplateKey());
        }
    }

    private List<String> sanitizeFileList(List<String> fileList,
                                          String prd,
                                          String projectType,
                                          String stackBackend,
                                          String stackFrontend,
                                          String stackDb,
                                          boolean allowBuiltinFallback,
                                          String backendRoot,
                                          String frontendRoot) {
        Set<String> dedup = new LinkedHashSet<>();
        if (fileList != null) {
            for (String path : fileList) {
                if (path == null) {
                    continue;
                }
                String normalized = path.trim().replace("\\", "/");
                if (normalized.isBlank()) {
                    continue;
                }
                if (normalized.startsWith("/") || normalized.contains("..")) {
                    continue;
                }
                dedup.add(normalized);
            }
        }
        if (dedup.isEmpty() && allowBuiltinFallback) {
            dedup.addAll(fallbackStructure(prd, projectType, stackBackend, stackFrontend, stackDb));
        }
        ensureDeploymentArtifacts(dedup, stackBackend, stackFrontend, backendRoot, frontendRoot);
        return new ArrayList<>(dedup);
    }

    private List<String> mergeTemplateFiles(List<String> templateFiles, List<String> plannedFiles) {
        Set<String> merged = new LinkedHashSet<>();
        if (templateFiles != null) {
            merged.addAll(templateFiles);
        }
        if (plannedFiles != null) {
            merged.addAll(plannedFiles);
        }
        return new ArrayList<>(merged);
    }

    private List<String> fallbackStructure(String prd, String projectType, String stackBackend, String stackFrontend, String stackDb) {
        List<String> files = new ArrayList<>();
        files.add("README.md");
        files.add(".gitignore");
        files.add("docker-compose.yml");
        files.add("docs/prd.md");
        files.add("docs/deploy.md");
        files.add("scripts/deploy.sh");
        files.add("scripts/start.sh");

        String backend = stackBackend == null ? "" : stackBackend.toLowerCase();
        List<String> modules = normalizeModules(modelService.guessModules("", prd, projectType));
        if (backend.contains("spring")) {
            files.add("backend/pom.xml");
            files.add("backend/mvnw");
            files.add("backend/mvnw.cmd");
            files.add("backend/Dockerfile");
            files.add("backend/src/main/java/com/example/Application.java");
            files.add("backend/src/main/resources/application.yml");
            files.add("backend/src/main/java/com/example/common/ApiResponse.java");
            files.add("backend/src/main/java/com/example/config/WebConfig.java");
            for (String module : modules) {
                String base = "backend/src/main/java/com/example/" + module;
                files.add(base + "/" + capitalize(module) + "Controller.java");
                files.add(base + "/" + capitalize(module) + "Service.java");
                files.add(base + "/" + capitalize(module) + "Repository.java");
                files.add(base + "/" + capitalize(module) + "Entity.java");
            }
        } else if (backend.contains("django")) {
            files.add("backend/requirements.txt");
            files.add("backend/Dockerfile");
            files.add("backend/manage.py");
            files.add("backend/config/__init__.py");
            files.add("backend/config/settings.py");
            files.add("backend/config/urls.py");
            files.add("backend/config/asgi.py");
            files.add("backend/config/wsgi.py");
            files.add("backend/users/__init__.py");
            files.add("backend/users/apps.py");
            files.add("backend/users/models.py");
            files.add("backend/users/views.py");
            files.add("backend/users/urls.py");
            files.add("backend/users/migrations/__init__.py");
        } else if (backend.contains("fastapi") || backend.contains("python")) {
            files.add("backend/requirements.txt");
            files.add("backend/Dockerfile");
            files.add("backend/app/__init__.py");
            files.add("backend/app/main.py");
            files.add("backend/app/config.py");
            files.add("backend/app/database.py");
            files.add("backend/app/models.py");
            files.add("backend/app/schemas.py");
            for (String module : modules) {
                files.add("backend/app/routers/" + module + ".py");
            }
        } else if (backend.contains("node") || backend.contains("express") || backend.contains("nestjs")) {
            files.add("backend/package.json");
            files.add("backend/Dockerfile");
            files.add("backend/src/main.ts");
            for (String module : modules) {
                String base = "backend/src/modules/" + module;
                files.add(base + "/" + module + ".controller.ts");
                files.add(base + "/" + module + ".service.ts");
                files.add(base + "/" + module + ".model.ts");
            }
        } else {
            files.add("backend/README.md");
        }

        String frontend = stackFrontend == null ? "" : stackFrontend.toLowerCase();
        if (frontend.contains("next")) {
            files.add("package.json");
            files.add("next.config.ts");
            files.add("app/layout.tsx");
            files.add("app/page.tsx");
            files.add("app/api/health/route.ts");
        } else if (frontend.contains("vue")) {
            files.add("frontend/package.json");
            files.add("frontend/index.html");
            files.add("frontend/vite.config.ts");
            files.add("frontend/Dockerfile");
            files.add("frontend/src/main.ts");
            files.add("frontend/src/App.vue");
            files.add("frontend/src/router/index.ts");
            files.add("frontend/src/api/client.ts");
            for (String module : modules) {
                files.add("frontend/src/pages/" + capitalize(module) + "Page.vue");
                files.add("frontend/src/stores/" + module + ".ts");
            }
        } else if (frontend.contains("react")) {
            files.add("frontend/package.json");
            files.add("frontend/index.html");
            files.add("frontend/vite.config.ts");
            files.add("frontend/Dockerfile");
            files.add("frontend/src/main.tsx");
            files.add("frontend/src/App.tsx");
            files.add("frontend/src/router/index.tsx");
            files.add("frontend/src/api/client.ts");
            for (String module : modules) {
                files.add("frontend/src/pages/" + capitalize(module) + "Page.tsx");
                files.add("frontend/src/stores/" + module + ".ts");
            }
        } else {
            files.add("frontend/README.md");
        }

        String db = stackDb == null ? "" : stackDb.toLowerCase();
        if (db.contains("mysql") || db.contains("postgres") || db.contains("sql")) {
            files.add("database/schema.sql");
            files.add("database/seed.sql");
            for (String module : modules) {
                files.add("database/migrations/V1__create_" + module + "_table.sql");
            }
        }
        return files;
    }

    private List<String> normalizeModules(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            out.add("core");
            return out;
        }
        int idx = 1;
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String normalized = item.trim().toLowerCase()
                    .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "_")
                    .replaceAll("^_+|_+$", "");
            if (normalized.isBlank()) {
                normalized = "module" + idx;
            }
            if (normalized.chars().anyMatch(ch -> ch > 127)) {
                normalized = "module" + idx;
            }
            if (!out.contains(normalized)) {
                out.add(normalized);
            }
            idx++;
            if (out.size() >= 5) {
                break;
            }
        }
        if (out.isEmpty()) {
            out.add("core");
        }
        return out;
    }

    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return "Core";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String detectGroup(String path) {
        return FileGroupDetector.detect(path);
    }

    private void ensureDeploymentArtifacts(Set<String> files,
                                          String stackBackend,
                                          String stackFrontend,
                                          String backendRoot,
                                          String frontendRoot) {
        files.add("docs/deploy.md");
        files.add("scripts/deploy.sh");
        files.add("scripts/start.sh");
        files.add("docker-compose.yml");
        files.add("README.md");
        String backend = stackBackend == null ? "" : stackBackend.toLowerCase();
        String frontend = stackFrontend == null ? "" : stackFrontend.toLowerCase();
        if (backend.contains("spring") || backend.contains("java")) {
            files.add(resolvePath(backendRoot, "pom.xml"));
            files.add(resolvePath(backendRoot, "mvnw"));
            files.add(resolvePath(backendRoot, "mvnw.cmd"));
            files.add(resolvePath(backendRoot, "Dockerfile"));
        } else if (backend.contains("django")) {
            files.add(resolvePath(backendRoot, "requirements.txt"));
            files.add(resolvePath(backendRoot, "Dockerfile"));
            files.add(resolvePath(backendRoot, "manage.py"));
            files.add(resolvePath(backendRoot, "config/settings.py"));
        } else if (backend.contains("fastapi") || backend.contains("python")) {
            files.add(resolvePath(backendRoot, "requirements.txt"));
            files.add(resolvePath(backendRoot, "Dockerfile"));
            files.add(resolvePath(backendRoot, "app/main.py"));
        } else if (backend.contains("node") || backend.contains("express") || backend.contains("nestjs")) {
            files.add(resolvePath(backendRoot, "package.json"));
            files.add(resolvePath(backendRoot, "Dockerfile"));
        }
        if (frontend.contains("next")) {
            files.add(resolvePath(frontendRoot, "package.json"));
            files.add(resolvePath(frontendRoot, "next.config.ts"));
            files.add(resolvePath(frontendRoot, "app/layout.tsx"));
            files.add(resolvePath(frontendRoot, "app/page.tsx"));
            files.add(resolvePath(frontendRoot, "Dockerfile"));
        } else if (frontend.contains("vue") || frontend.contains("react") || frontend.contains("uni")) {
            files.add(resolvePath(frontendRoot, "package.json"));
            files.add(resolvePath(frontendRoot, "index.html"));
            files.add(resolvePath(frontendRoot, "vite.config.ts"));
            files.add(resolvePath(frontendRoot, "Dockerfile"));
        }
    }

    private StructureCompleteness validateStructureCompleteness(List<String> fileList,
                                                                String stackBackend,
                                                                String stackFrontend,
                                                                String stackDb,
                                                                String backendRoot,
                                                                String frontendRoot) {
        Set<String> missing = new LinkedHashSet<>();
        Set<String> files = new LinkedHashSet<>(fileList == null ? List.of() : fileList);
        require(files, missing, "README.md");
        require(files, missing, "docker-compose.yml");
        require(files, missing, "scripts/start.sh");
        require(files, missing, "scripts/deploy.sh");
        require(files, missing, "docs/deploy.md");

        String backend = stackBackend == null ? "" : stackBackend.toLowerCase();
        if (backend.contains("spring") || backend.contains("java")) {
            require(files, missing, resolvePath(backendRoot, "pom.xml"));
            require(files, missing, resolvePath(backendRoot, "mvnw"));
            require(files, missing, resolvePath(backendRoot, "Dockerfile"));
            require(files, missing, resolvePath(backendRoot, "src/main/resources/application.yml"));
            String javaRoot = resolvePath(backendRoot, "src/main/java/");
            if (files.stream().noneMatch(p -> p.startsWith(javaRoot) && p.endsWith("/Application.java"))) {
                missing.add(resolvePath(backendRoot, "src/main/java/**/Application.java"));
            }
        } else if (backend.contains("django")) {
            require(files, missing, resolvePath(backendRoot, "requirements.txt"));
            require(files, missing, resolvePath(backendRoot, "Dockerfile"));
            require(files, missing, resolvePath(backendRoot, "manage.py"));
            require(files, missing, resolvePath(backendRoot, "config/settings.py"));
        } else if (backend.contains("fastapi") || backend.contains("python")) {
            require(files, missing, resolvePath(backendRoot, "requirements.txt"));
            require(files, missing, resolvePath(backendRoot, "Dockerfile"));
            require(files, missing, resolvePath(backendRoot, "app/main.py"));
        } else if (backend.contains("node") || backend.contains("express") || backend.contains("nestjs")) {
            require(files, missing, resolvePath(backendRoot, "package.json"));
            require(files, missing, resolvePath(backendRoot, "Dockerfile"));
            require(files, missing, resolvePath(backendRoot, "src/main.ts"));
        }

        String frontend = stackFrontend == null ? "" : stackFrontend.toLowerCase();
        if (frontend.contains("next")) {
            require(files, missing, resolvePath(frontendRoot, "package.json"));
            require(files, missing, resolvePath(frontendRoot, "next.config.ts"));
            require(files, missing, resolvePath(frontendRoot, "app/layout.tsx"));
            require(files, missing, resolvePath(frontendRoot, "app/page.tsx"));
        } else if (frontend.contains("vue")) {
            require(files, missing, resolvePath(frontendRoot, "package.json"));
            require(files, missing, resolvePath(frontendRoot, "index.html"));
            require(files, missing, resolvePath(frontendRoot, "vite.config.ts"));
            require(files, missing, resolvePath(frontendRoot, "Dockerfile"));
            require(files, missing, resolvePath(frontendRoot, "src/main.ts"));
            require(files, missing, resolvePath(frontendRoot, "src/App.vue"));
        } else if (frontend.contains("react")) {
            require(files, missing, resolvePath(frontendRoot, "package.json"));
            require(files, missing, resolvePath(frontendRoot, "index.html"));
            require(files, missing, resolvePath(frontendRoot, "vite.config.ts"));
            require(files, missing, resolvePath(frontendRoot, "Dockerfile"));
            require(files, missing, resolvePath(frontendRoot, "src/main.tsx"));
            require(files, missing, resolvePath(frontendRoot, "src/App.tsx"));
        }

        String db = stackDb == null ? "" : stackDb.toLowerCase();
        if (db.contains("mysql") || db.contains("postgres") || db.contains("sql")) {
            require(files, missing, "database/schema.sql");
        }
        return new StructureCompleteness(missing.isEmpty(), new ArrayList<>(missing));
    }

    private void require(Set<String> files, Set<String> missing, String expected) {
        if (!files.contains(expected)) {
            missing.add(expected);
        }
    }

    private String resolvePath(String root, String relativePath) {
        if (root == null || root.isBlank() || ".".equals(root)) {
            return relativePath;
        }
        return root + "/" + relativePath;
    }

    private List<FilePlanItem> buildFilePlan(List<String> mergedFiles, List<String> templateFiles, String templateKey) {
        Set<String> templateFileSet = new LinkedHashSet<>(templateFiles == null ? List.of() : templateFiles);
        Map<String, FilePlanItem> filePlanMap = new LinkedHashMap<>();
        if (templateFiles != null) {
            for (String path : templateFiles) {
                filePlanMap.put(path, createPlanItem(path, 20, templateKey == null ? "template_repo" : "template_repo:" + templateKey));
            }
        }
        if (mergedFiles != null) {
            for (String path : mergedFiles) {
                filePlanMap.putIfAbsent(path, createPlanItem(path, 50, templateFileSet.contains(path) ? "template_repo" : "planned_from_structure"));
            }
        }
        return new ArrayList<>(filePlanMap.values());
    }

    private FilePlanItem createPlanItem(String path, int priority, String reason) {
        FilePlanItem item = new FilePlanItem();
        item.setPath(path);
        item.setGroup(detectGroup(path));
        item.setPriority(priority);
        item.setReason(reason);
        return item;
    }

    private String normalizeCodegenEngine(String value) {
        if (value == null || value.isBlank()) {
            return "llm";
        }
        String normalized = value.trim().toLowerCase();
        return switch (normalized) {
            case "llm", "jeecg_rule", "hybrid", "internal_service" -> normalized;
            default -> "llm";
        };
    }

    private void enforceFrontendBusinessCoverage(String prd,
                                                 String stackFrontend,
                                                 String frontendRoot,
                                                 List<FilePlanItem> filePlan,
                                                 AgentExecutionContext context) throws Exception {
        int expectedPageCount = parseExpectedPageCount(prd);
        if (expectedPageCount < 3 || filePlan == null || filePlan.isEmpty()) {
            return;
        }
        String frontend = stackFrontend == null ? "" : stackFrontend.toLowerCase();
        if (!(frontend.contains("vue") || frontend.contains("react") || frontend.contains("next"))) {
            return;
        }
        int plannedBusinessPageFiles = (int) filePlan.stream()
                .map(FilePlanItem::getPath)
                .filter(path -> path != null && !path.isBlank())
                .filter(path -> isBusinessPagePath(path, frontendRoot, frontend))
                .count();
        context.logInfo("Frontend coverage check: expectedPages=" + expectedPageCount
                + ", plannedBusinessFiles=" + plannedBusinessPageFiles);
        if (plannedBusinessPageFiles > 0) {
            return;
        }
        throw new BusinessException(
                ErrorCodes.TASK_VALIDATION_ERROR,
                "前端页面规划不足：PRD包含" + expectedPageCount + "个页面，但生成计划仅包含模板壳文件，缺少业务页面文件"
        );
    }

    private int parseExpectedPageCount(String prd) {
        if (prd == null || prd.isBlank()) {
            return 0;
        }
        try {
            JsonNode prdNode = objectMapper.readTree(prd);
            JsonNode pages = prdNode.path("pages");
            return pages.isArray() ? pages.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int parseExpectedFeatureCount(String prd) {
        if (prd == null || prd.isBlank()) {
            return 0;
        }
        try {
            JsonNode prdNode = objectMapper.readTree(prd);
            JsonNode features = prdNode.path("features");
            return features.isArray() ? features.size() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean isBusinessPagePath(String path, String frontendRoot, String frontend) {
        String normalizedRoot = (frontendRoot == null || frontendRoot.isBlank() || ".".equals(frontendRoot))
                ? ""
                : frontendRoot + "/";
        String relative = normalizedRoot.isEmpty() ? path : path.startsWith(normalizedRoot)
                ? path.substring(normalizedRoot.length()) : path;
        if (relative.equals(path) && !normalizedRoot.isEmpty()) {
            return false;
        }
        String lower = relative.toLowerCase();
        if (frontend.contains("next")) {
            return lower.startsWith("app/") && lower.endsWith("/page.tsx")
                    || lower.startsWith("app/") && lower.endsWith("/page.jsx");
        }
        return lower.startsWith("src/pages/") || lower.startsWith("src/views/");
    }

    private void enforceBackendBusinessCoverage(String prd,
                                                String stackBackend,
                                                String backendRoot,
                                                List<FilePlanItem> filePlan,
                                                AgentExecutionContext context) {
        int expectedFeatureCount = parseExpectedFeatureCount(prd);
        if (expectedFeatureCount < 4 || filePlan == null || filePlan.isEmpty()) {
            return;
        }
        String backend = stackBackend == null ? "" : stackBackend.toLowerCase();
        if (!(backend.contains("spring")
                || backend.contains("django")
                || backend.contains("fastapi")
                || backend.contains("python")
                || backend.contains("node")
                || backend.contains("express")
                || backend.contains("nestjs"))) {
            return;
        }
        long plannedBackendBusinessFiles = filePlan.stream()
                .filter(item -> item.getPath() != null && !item.getPath().isBlank())
                .filter(item -> "backend".equals(item.getGroup()))
                .filter(item -> isBackendBusinessPath(item.getPath(), backendRoot))
                .filter(item -> item.getReason() == null || !item.getReason().startsWith("template_repo:"))
                .count();
        context.logInfo("Backend coverage check: expectedFeatures=" + expectedFeatureCount
                + ", plannedBusinessFiles=" + plannedBackendBusinessFiles);
        if (plannedBackendBusinessFiles > 0) {
            return;
        }
        throw new BusinessException(
                ErrorCodes.TASK_VALIDATION_ERROR,
                "后端业务规划不足：PRD包含" + expectedFeatureCount + "个功能点，但生成计划缺少非模板业务后端文件"
        );
    }

    private boolean isBackendBusinessPath(String path, String backendRoot) {
        String normalizedRoot = (backendRoot == null || backendRoot.isBlank() || ".".equals(backendRoot))
                ? ""
                : backendRoot + "/";
        String relative = normalizedRoot.isEmpty() ? path : path.startsWith(normalizedRoot)
                ? path.substring(normalizedRoot.length()) : path;
        if (relative.equals(path) && !normalizedRoot.isEmpty()) {
            return false;
        }
        String lower = relative.toLowerCase();
        if (lower.startsWith("src/main/java/")) {
            return !lower.contains("/config/")
                    && !lower.contains("/health/")
                    && !lower.endsWith("application.java");
        }
        if (lower.startsWith("app/")) {
            return lower.contains("/routers/")
                    || lower.contains("/views.py")
                    || lower.contains("/controllers/")
                    || lower.contains("/services/")
                    || lower.contains("/models.py");
        }
        if (lower.startsWith("src/")) {
            return lower.contains("/modules/")
                    || lower.contains("/controllers/")
                    || lower.contains("/services/");
        }
        return false;
    }

    private record StructureCompleteness(boolean passed, List<String> missingFiles) {
    }
}
