package com.smartark.gateway.controller;

import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import com.smartark.gateway.service.ModelRouterService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/models")
public class ModelAdminController {

    private final ModelRouterService modelRouterService;

    public ModelAdminController(ModelRouterService modelRouterService) {
        this.modelRouterService = modelRouterService;
    }

    /**
     * Dashboard: all models with config + today's usage + remaining quota.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<List<Map<String, Object>>> dashboard() {
        return ResponseEntity.ok(modelRouterService.dashboard());
    }

    /**
     * List all registered models.
     */
    @GetMapping
    public ResponseEntity<List<ModelRegistryEntity>> listModels() {
        return ResponseEntity.ok(modelRouterService.listAll());
    }

    /**
     * Get a single model by name.
     */
    @GetMapping("/{modelName}")
    public ResponseEntity<ModelRegistryEntity> getModel(@PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add or update a model.
     * Body: { modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled }
     */
    @PostMapping
    public ResponseEntity<ModelRegistryEntity> createOrUpdateModel(@RequestBody Map<String, Object> body) {
        String modelName = (String) body.get("modelName");
        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String displayName = (String) body.getOrDefault("displayName", modelName);
        String provider = (String) body.getOrDefault("provider", "dashscope");
        String modelRole = (String) body.getOrDefault("modelRole", "code");
        Long dailyTokenLimit = body.containsKey("dailyTokenLimit")
                ? ((Number) body.get("dailyTokenLimit")).longValue() : 0L;
        Integer priority = body.containsKey("priority")
                ? ((Number) body.get("priority")).intValue() : 100;
        Boolean enabled = body.containsKey("enabled")
                ? (Boolean) body.get("enabled") : true;

        ModelRegistryEntity saved = modelRouterService.upsertModel(
                modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled);
        return ResponseEntity.ok(saved);
    }

    /**
     * Update specific fields of a model.
     */
    @PutMapping("/{modelName}")
    public ResponseEntity<ModelRegistryEntity> updateModel(
            @PathVariable String modelName,
            @RequestBody Map<String, Object> body) {
        if (modelRouterService.findByName(modelName).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String displayName = (String) body.get("displayName");
        String provider = (String) body.get("provider");
        String modelRole = (String) body.get("modelRole");
        Long dailyTokenLimit = body.containsKey("dailyTokenLimit")
                ? ((Number) body.get("dailyTokenLimit")).longValue() : null;
        Integer priority = body.containsKey("priority")
                ? ((Number) body.get("priority")).intValue() : null;
        Boolean enabled = body.containsKey("enabled")
                ? (Boolean) body.get("enabled") : null;

        ModelRegistryEntity saved = modelRouterService.upsertModel(
                modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled);
        return ResponseEntity.ok(saved);
    }

    /**
     * Delete a model from registry.
     */
    @DeleteMapping("/{modelName}")
    public ResponseEntity<Void> deleteModel(@PathVariable String modelName) {
        modelRouterService.deleteModel(modelName);
        return ResponseEntity.noContent().build();
    }

    /**
     * Toggle model enabled/disabled.
     */
    @PostMapping("/{modelName}/toggle")
    public ResponseEntity<ModelRegistryEntity> toggleModel(@PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(model -> {
                    ModelRegistryEntity updated = modelRouterService.upsertModel(
                            modelName, null, null, null, null, null, !model.getEnabled());
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resolve which model would be used for a given role right now.
     */
    @GetMapping("/resolve/{role}")
    public ResponseEntity<Map<String, String>> resolveModel(@PathVariable String role) {
        String model = modelRouterService.resolve(role);
        return ResponseEntity.ok(Map.of("role", role, "model", model));
    }

    /**
     * Get today's usage for all models.
     */
    @GetMapping("/usage/today")
    public ResponseEntity<Map<String, ModelUsageDailyEntity>> todayUsage() {
        return ResponseEntity.ok(modelRouterService.getTodayUsage());
    }

    /**
     * Get usage history for a date range.
     */
    @GetMapping("/usage/history")
    public ResponseEntity<List<ModelUsageDailyEntity>> usageHistory(
            @RequestParam(required = false) String modelName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(modelRouterService.getUsageHistory(modelName, start, end));
    }
}
