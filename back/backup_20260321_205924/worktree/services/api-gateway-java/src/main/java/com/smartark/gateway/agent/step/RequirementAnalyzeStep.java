package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.FilePlanItem;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class RequirementAnalyzeStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(RequirementAnalyzeStep.class);
    
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public RequirementAnalyzeStep(ModelService modelService, ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "requirement_analyze";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        logger.info("Analyzing requirements and planning project structure...");
        
        JsonNode reqJson = objectMapper.readTree(context.getSpec().getRequirementJson());
        String prd = reqJson.path("prd").asText("");
        String projectType = reqJson.path("projectType").asText("");
        String stackBackend = reqJson.at("/stack/backend").asText("springboot");
        String stackFrontend = reqJson.at("/stack/frontend").asText("vue3");
        String stackDb = reqJson.at("/stack/db").asText("mysql");
        
        List<String> fileList;
        try {
            fileList = modelService.generateProjectStructure(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    prd, stackBackend, stackFrontend, stackDb, context.getInstructions()
            );
        } catch (Exception e) {
            logger.warn("Project structure generation failed, fallback to builtin structure", e);
            fileList = fallbackStructure(prd, projectType, stackBackend, stackFrontend, stackDb);
        }
        fileList = sanitizeFileList(fileList, prd, projectType, stackBackend, stackFrontend, stackDb);

        List<FilePlanItem> filePlan = new ArrayList<>();
        for (String path : fileList) {
            FilePlanItem item = new FilePlanItem();
            item.setPath(path);
            item.setGroup(detectGroup(path));
            item.setPriority(50);
            item.setReason("fallback_from_path_list");
            filePlan.add(item);
        }

        context.setFileList(fileList);
        context.setFilePlan(filePlan);
        logger.info("Generated {} files in plan.", fileList.size());
    }

    private List<String> sanitizeFileList(List<String> fileList, String prd, String projectType, String stackBackend, String stackFrontend, String stackDb) {
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
        if (dedup.isEmpty()) {
            dedup.addAll(fallbackStructure(prd, projectType, stackBackend, stackFrontend, stackDb));
        }
        ensureDeploymentArtifacts(dedup);
        return new ArrayList<>(dedup);
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
        } else if (backend.contains("node") || backend.contains("express") || backend.contains("nestjs")) {
            files.add("backend/package.json");
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
        if (frontend.contains("vue")) {
            files.add("frontend/package.json");
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
        String p = path == null ? "" : path.toLowerCase();
        if (p.contains("backend/")) return "backend";
        if (p.contains("frontend/")) return "frontend";
        if (p.endsWith(".sql") || p.contains("/db/") || p.contains("database")) return "database";
        if (p.contains("docker") || p.contains(".yml") || p.startsWith("scripts/") || p.endsWith(".sh") || p.endsWith(".bat")) return "infra";
        if (p.startsWith("docs/") || p.endsWith("readme.md")) return "docs";
        return "backend";
    }

    private void ensureDeploymentArtifacts(Set<String> files) {
        files.add("docs/deploy.md");
        files.add("scripts/deploy.sh");
        files.add("scripts/start.sh");
        files.add("docker-compose.yml");
    }
}
