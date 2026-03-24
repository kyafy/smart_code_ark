package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FrontendRuntimePlanService {
    private static final Pattern NPM_RUN_SCRIPT_PATTERN = Pattern.compile("npm\\s+run\\s+([\\w:-]+)");

    private final ObjectMapper objectMapper;
    private final TemplateRepoService templateRepoService;

    public FrontendRuntimePlanService(ObjectMapper objectMapper, @Autowired(required = false) TemplateRepoService templateRepoService) {
        this.objectMapper = objectMapper;
        this.templateRepoService = templateRepoService;
    }

    public Optional<FrontendRuntimePlan> resolvePlan(TaskEntity task, Path workspaceDir) {
        Optional<TemplateRepoService.TemplateSelection> templateSelection = resolveTemplateSelection(task);
        List<Path> candidates = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();

        templateSelection.ifPresent(selection -> appendTemplateCandidates(selection, workspaceDir, candidates, dedupe));
        appendFallbackCandidates(workspaceDir, candidates, dedupe);

        for (Path candidate : candidates) {
            Path packageJsonPath = candidate.resolve("package.json");
            if (!Files.exists(packageJsonPath)) {
                continue;
            }
            Map<String, String> scripts = readPackageScripts(packageJsonPath);
            if (scripts.isEmpty()) {
                continue;
            }
            String startScript = resolvePreferredStartScript(scripts.keySet(), templateSelection.orElse(null));
            if (startScript == null) {
                continue;
            }
            List<String> preStartScripts = resolvePreStartScripts(scripts.keySet(), templateSelection.orElse(null), startScript);
            return Optional.of(new FrontendRuntimePlan(candidate, startScript, preStartScripts));
        }
        return Optional.empty();
    }

    public String installCommand() {
        return "npm install --prefer-offline 2>&1";
    }

    public String npmRunCommand(String script) {
        return "npm run " + script + " 2>&1";
    }

    public String buildBootCommand(String startScript, String logFileName) {
        String base = "npm run " + startScript;
        if ("dev".equals(startScript) || "preview".equals(startScript) || startScript.startsWith("dev:")) {
            base += " -- --host 0.0.0.0";
        }
        return base + " > " + logFileName + " 2>&1";
    }

    private Optional<TemplateRepoService.TemplateSelection> resolveTemplateSelection(TaskEntity task) {
        if (task == null || task.getTemplateId() == null || task.getTemplateId().isBlank() || templateRepoService == null) {
            return Optional.empty();
        }
        return templateRepoService.resolveTemplateById(task.getTemplateId());
    }

    private void appendTemplateCandidates(
            TemplateRepoService.TemplateSelection selection,
            Path workspaceDir,
            List<Path> candidates,
            Set<String> dedupe
    ) {
        Map<String, String> paths = selection.metadata() == null ? Map.of() : selection.metadata().paths();
        appendCandidate(candidates, dedupe, resolveTemplatePath(workspaceDir, paths.get("frontend")));
        appendCandidate(candidates, dedupe, resolveTemplatePath(workspaceDir, paths.get("app")));
    }

    private void appendFallbackCandidates(Path workspaceDir, List<Path> candidates, Set<String> dedupe) {
        for (String candidate : List.of("frontend", "frontend-mobile", "frontend-web", "web", "client", "app", ".")) {
            Path candidatePath = ".".equals(candidate) ? workspaceDir : workspaceDir.resolve(candidate);
            appendCandidate(candidates, dedupe, candidatePath);
        }
    }

    private void appendCandidate(List<Path> candidates, Set<String> dedupe, Path candidatePath) {
        if (candidatePath == null) {
            return;
        }
        String key = candidatePath.toAbsolutePath().normalize().toString();
        if (dedupe.add(key)) {
            candidates.add(candidatePath);
        }
    }

    private Path resolveTemplatePath(Path workspaceDir, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || ".".equals(relativePath.trim())) {
            return workspaceDir;
        }
        return workspaceDir.resolve(relativePath).normalize();
    }

    private Map<String, String> readPackageScripts(Path packageJsonPath) {
        try {
            JsonNode root = objectMapper.readTree(Files.readString(packageJsonPath, StandardCharsets.UTF_8));
            JsonNode scriptsNode = root.path("scripts");
            if (!scriptsNode.isObject()) {
                return Map.of();
            }
            Map<String, String> scripts = new LinkedHashMap<>();
            scriptsNode.fields().forEachRemaining(entry -> {
                String value = entry.getValue() == null ? null : entry.getValue().asText(null);
                if (value != null && !value.isBlank()) {
                    scripts.put(entry.getKey(), value.trim());
                }
            });
            return scripts;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String resolvePreferredStartScript(
            Set<String> availableScripts,
            TemplateRepoService.TemplateSelection templateSelection
    ) {
        for (String runScript : extractRunScripts(templateSelection)) {
            if (availableScripts.contains(runScript) && isStartScript(runScript)) {
                return runScript;
            }
        }

        for (String candidate : List.of("dev", "dev:h5", "preview", "start")) {
            if (availableScripts.contains(candidate)) {
                return candidate;
            }
        }

        return availableScripts.stream()
                .filter(this::isStartScript)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private List<String> resolvePreStartScripts(
            Set<String> availableScripts,
            TemplateRepoService.TemplateSelection templateSelection,
            String startScript
    ) {
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        for (String runScript : extractRunScripts(templateSelection)) {
            if (!availableScripts.contains(runScript) || runScript.equals(startScript)) {
                continue;
            }
            if (isPreStartScript(runScript)) {
                scripts.add(runScript);
            }
        }
        return new ArrayList<>(scripts);
    }

    private List<String> extractRunScripts(TemplateRepoService.TemplateSelection templateSelection) {
        if (templateSelection == null || templateSelection.metadata() == null || templateSelection.metadata().run() == null) {
            return List.of();
        }
        LinkedHashSet<String> scripts = new LinkedHashSet<>();
        for (String runCommand : templateSelection.metadata().run().values()) {
            if (runCommand == null || runCommand.isBlank()) {
                continue;
            }
            Matcher matcher = NPM_RUN_SCRIPT_PATTERN.matcher(runCommand);
            while (matcher.find()) {
                String script = matcher.group(1);
                if (script != null && !script.isBlank()) {
                    scripts.add(script.trim());
                }
            }
        }
        return new ArrayList<>(scripts);
    }

    private boolean isStartScript(String script) {
        return "dev".equals(script)
                || "dev:h5".equals(script)
                || "preview".equals(script)
                || "start".equals(script)
                || script.startsWith("dev:");
    }

    private boolean isPreStartScript(String script) {
        return "prisma:generate".equals(script) || "prepare".equals(script);
    }

    public record FrontendRuntimePlan(
            Path projectDir,
            String startScript,
            List<String> preStartScripts
    ) {
    }
}
