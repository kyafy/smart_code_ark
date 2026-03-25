package com.smartark.gateway.controller;

import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.service.ModelService;
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
    private final ModelService modelService;

    public ModelAdminController(ModelRouterService modelRouterService, ModelService modelService) {
        this.modelRouterService = modelRouterService;
        this.modelService = modelService;
    }

    /**
     * Dashboard: all models with config + today's usage + remaining quota.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.dashboard()));
    }

    /**
     * List all registered models.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ModelRegistryEntity>>> listModels() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.listAll()));
    }

    /**
     * Get a single model by name.
     */
    @GetMapping("/{modelName}")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> getModel(@PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(model -> ResponseEntity.ok(ApiResponse.success(model)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add or update a model.
     * Body: { modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled }
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> createOrUpdateModel(@RequestBody Map<String, Object> body) {
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
        String baseUrl = body.containsKey("baseUrl") ? (String) body.get("baseUrl") : null;
        String apiKey = body.containsKey("apiKey") ? (String) body.get("apiKey") : null;

        ModelRegistryEntity saved = modelRouterService.upsertModel(
                modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled, baseUrl, apiKey);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    /**
     * Update specific fields of a model.
     */
    @PutMapping("/{modelName}")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> updateModel(
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
        String baseUrl = body.containsKey("baseUrl") ? (String) body.get("baseUrl") : null;
        String apiKey = body.containsKey("apiKey") ? (String) body.get("apiKey") : null;

        ModelRegistryEntity saved = modelRouterService.upsertModel(
                modelName, displayName, provider, modelRole, dailyTokenLimit, priority, enabled, baseUrl, apiKey);
        return ResponseEntity.ok(ApiResponse.success(saved));
    }

    /**
     * Delete a model from registry.
     */
    @DeleteMapping("/{modelName}")
    public ResponseEntity<ApiResponse<Void>> deleteModel(@PathVariable String modelName) {
        modelRouterService.deleteModel(modelName);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Toggle model enabled/disabled.
     */
    @PostMapping("/{modelName}/toggle")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> toggleModel(@PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(model -> {
                    ModelRegistryEntity updated = modelRouterService.upsertModel(
                            modelName, null, null, null, null, null, !model.getEnabled(), null, null);
                    return ResponseEntity.ok(ApiResponse.success(updated));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{modelName}/test")
    public ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> testModelConnectivity(
            @PathVariable String modelName,
            @RequestBody(required = false) Map<String, Object> body) {
        var modelOpt = modelRouterService.findByName(modelName);
        if (modelOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String prompt = body == null ? null : (String) body.get("prompt");
        Integer timeoutMs = null;
        if (body != null && body.get("timeoutMs") instanceof Number n) {
            timeoutMs = n.intValue();
        }
        String baseUrl = body == null ? null : (String) body.get("baseUrl");
        String apiKey = body == null ? null : (String) body.get("apiKey");
        ModelRegistryEntity model = modelOpt.get();
        var result = modelService.testModelConnectivity(
                modelName,
                model.getProvider(),
                prompt,
                timeoutMs,
                baseUrl,
                apiKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/test-connectivity")
    public ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> testConnectivityMvp(
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> req = body == null ? Map.of() : body;
        String modelName = req.get("modelName") instanceof String m ? m : null;
        if (modelName == null || modelName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String provider = req.get("provider") instanceof String p && !p.isBlank() ? p : "dashscope";
        String prompt = req.get("prompt") instanceof String p ? p : null;
        Integer timeoutMs = null;
        if (req.get("timeoutMs") instanceof Number n) {
            timeoutMs = n.intValue();
        }
        String baseUrl = req.get("baseUrl") instanceof String b ? b : null;
        String apiKey = req.get("apiKey") instanceof String k ? k : null;
        var result = modelService.testModelConnectivity(
                modelName,
                provider,
                prompt,
                timeoutMs,
                baseUrl,
                apiKey);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Resolve which model would be used for a given role right now.
     */
    @GetMapping("/resolve/{role}")
    public ResponseEntity<ApiResponse<Map<String, String>>> resolveModel(@PathVariable String role) {
        String model = modelRouterService.resolve(role);
        return ResponseEntity.ok(ApiResponse.success(Map.of("role", role, "model", model)));
    }

    /**
     * Get today's usage for all models.
     */
    @GetMapping("/usage/today")
    public ResponseEntity<ApiResponse<Map<String, ModelUsageDailyEntity>>> todayUsage() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.getTodayUsage()));
    }

    /**
     * Get usage history for a date range.
     */
    @GetMapping("/usage/history")
    public ResponseEntity<ApiResponse<List<ModelUsageDailyEntity>>> usageHistory(
            @RequestParam(required = false) String modelName,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.getUsageHistory(modelName, start, end)));
    }
}
