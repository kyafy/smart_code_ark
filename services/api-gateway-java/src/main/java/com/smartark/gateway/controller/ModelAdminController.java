package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.ModelRouterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Model Admin", description = "Model registry, route resolution, connectivity tests, and usage reporting")
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
    @Operation(summary = "Model dashboard", description = "Returns model configuration and today's usage summary.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Success")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.dashboard()));
    }

    /**
     * List all registered models.
     */
    @GetMapping
    @Operation(summary = "List all models", description = "Lists all model registry records ordered by priority.")
    public ResponseEntity<ApiResponse<List<ModelRegistryEntity>>> listModels() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.listAll()));
    }

    /**
     * Get a single model by name.
     */
    @GetMapping("/{modelName}")
    @Operation(summary = "Get model by name")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Model not found")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> getModel(
            @Parameter(description = "Model name, for example qwen-plus", required = true)
            @PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(model -> ResponseEntity.ok(ApiResponse.success(model)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Add or update a model.
     * Body: { modelName, displayName, provider, modelRole(chat/code/paper/embedding), dailyTokenLimit, priority, enabled }
     */
    @PostMapping
    @Operation(summary = "Create or update model", description = "Upserts a model record by modelName.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "Model upsert payload",
            content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Map.class),
                    examples = @ExampleObject(
                            value = """
                                    {
                                      "modelName": "qwen-plus",
                                      "displayName": "Qwen 3.5 Plus",
                                      "provider": "dashscope",
                                      "modelRole": "paper",
                                      "dailyTokenLimit": 20000000,
                                      "priority": 10,
                                      "enabled": true,
                                      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
                                      "apiKey": "sk-***"
                                    }
                                    """
                    )
            )
    )
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
    @Operation(summary = "Update model fields", description = "Updates selected fields for an existing model.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Model not found")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> updateModel(
            @Parameter(description = "Model name", required = true) @PathVariable String modelName,
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
    @Operation(summary = "Delete model")
    public ResponseEntity<ApiResponse<Void>> deleteModel(
            @Parameter(description = "Model name", required = true) @PathVariable String modelName) {
        modelRouterService.deleteModel(modelName);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * Toggle model enabled/disabled.
     */
    @PostMapping("/{modelName}/toggle")
    @Operation(summary = "Toggle model enabled status")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Toggled")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Model not found")
    public ResponseEntity<ApiResponse<ModelRegistryEntity>> toggleModel(
            @Parameter(description = "Model name", required = true) @PathVariable String modelName) {
        return modelRouterService.findByName(modelName)
                .map(model -> {
                    ModelRegistryEntity updated = modelRouterService.upsertModel(
                            modelName, null, null, null, null, null, !model.getEnabled(), null, null);
                    return ResponseEntity.ok(ApiResponse.success(updated));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{modelName}/test")
    @Operation(summary = "Test model connectivity", description = "Tests the registered model with optional override credentials.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Test result returned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Model not found")
    public ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> testModelConnectivity(
            @Parameter(description = "Model name", required = true) @PathVariable String modelName,
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
    @Operation(summary = "Direct connectivity test", description = "Tests connectivity without requiring a pre-registered model.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Test result returned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Bad request")
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
    @Operation(summary = "Resolve model by role", description = "Returns the model currently selected for a role.")
    public ResponseEntity<ApiResponse<Map<String, String>>> resolveModel(
            @Parameter(description = "Role: chat/code/paper/embedding", required = true)
            @PathVariable String role) {
        String model = modelRouterService.resolve(role);
        return ResponseEntity.ok(ApiResponse.success(Map.of("role", role, "model", model)));
    }

    /**
     * Get today's usage for all models.
     */
    @GetMapping("/usage/today")
    @Operation(summary = "Get today's usage")
    public ResponseEntity<ApiResponse<Map<String, ModelUsageDailyEntity>>> todayUsage() {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.getTodayUsage()));
    }

    /**
     * Get usage history for a date range.
     */
    @GetMapping("/usage/history")
    @Operation(summary = "Get usage history", description = "Query usage history for a date range, optionally filtered by modelName.")
    public ResponseEntity<ApiResponse<List<ModelUsageDailyEntity>>> usageHistory(
            @Parameter(description = "Optional model name filter", required = false) @RequestParam(required = false) String modelName,
            @Parameter(description = "Start date, format: YYYY-MM-DD", required = true) @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "End date, format: YYYY-MM-DD", required = true) @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(modelRouterService.getUsageHistory(modelName, start, end)));
    }
}
