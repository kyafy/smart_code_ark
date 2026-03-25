package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api", "/api/v1"})
@Tag(name = "Health", description = "Service health check API")
public class HealthController {
    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("service", "api-gateway", "status", "UP"));
    }
}
