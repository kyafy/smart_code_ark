package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.service.ModelService;
import com.smartark.gateway.service.ModelRouterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelAdminControllerTest {
    @Mock
    private ModelRouterService modelRouterService;
    @Mock
    private ModelService modelService;

    @Test
    void testModelConnectivity_shouldReturnNotFoundWhenModelMissing() {
        when(modelRouterService.findByName("qwen-plus")).thenReturn(Optional.empty());
        ModelAdminController controller = new ModelAdminController(modelRouterService, modelService);

        ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> response =
                controller.testModelConnectivity("qwen-plus", Map.of());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void testModelConnectivity_shouldCallServiceAndReturnResult() {
        ModelRegistryEntity entity = new ModelRegistryEntity();
        entity.setModelName("qwen-plus");
        entity.setProvider("dashscope");
        when(modelRouterService.findByName("qwen-plus")).thenReturn(Optional.of(entity));
        ModelService.ConnectivityTestResult result = new ModelService.ConnectivityTestResult(
                true, "qwen-plus", "dashscope", 1234, "OK", null, null, null
        );
        when(modelService.testModelConnectivity(
                eq("qwen-plus"), eq("dashscope"), eq("ping"), eq(30000), eq("https://dashscope.aliyuncs.com/compatible-mode"), eq("sk-test")
        )).thenReturn(result);
        ModelAdminController controller = new ModelAdminController(modelRouterService, modelService);

        ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> response = controller.testModelConnectivity(
                "qwen-plus",
                Map.of(
                        "prompt", "ping",
                        "timeoutMs", 30000,
                        "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode",
                        "apiKey", "sk-test"
                )
        );

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().code());
        assertNotNull(response.getBody().data());
        assertTrue(response.getBody().data().ok());
        verify(modelService).testModelConnectivity(
                eq("qwen-plus"), eq("dashscope"), eq("ping"), eq(30000), eq("https://dashscope.aliyuncs.com/compatible-mode"), eq("sk-test")
        );
    }

    @Test
    void testConnectivityMvp_shouldReturnBadRequestWhenModelNameMissing() {
        ModelAdminController controller = new ModelAdminController(modelRouterService, modelService);

        ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> response = controller.testConnectivityMvp(Map.of(
                "prompt", "ping"
        ));

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void testConnectivityMvp_shouldCallServiceWithoutRegistryDependency() {
        ModelService.ConnectivityTestResult result = new ModelService.ConnectivityTestResult(
                true, "qwen3-max", "dashscope", 321, "OK", null, null, null
        );
        when(modelService.testModelConnectivity(
                eq("qwen3-max"), eq("dashscope"), eq("ping"), eq(5000), eq("https://dashscope.aliyuncs.com/compatible-mode"), eq("sk-direct")
        )).thenReturn(result);
        ModelAdminController controller = new ModelAdminController(modelRouterService, modelService);

        ResponseEntity<ApiResponse<ModelService.ConnectivityTestResult>> response = controller.testConnectivityMvp(Map.of(
                "modelName", "qwen3-max",
                "provider", "dashscope",
                "prompt", "ping",
                "timeoutMs", 5000,
                "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode",
                "apiKey", "sk-direct"
        ));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(0, response.getBody().code());
        assertNotNull(response.getBody().data());
        assertTrue(response.getBody().data().ok());
        verify(modelService).testModelConnectivity(
                eq("qwen3-max"), eq("dashscope"), eq("ping"), eq(5000), eq("https://dashscope.aliyuncs.com/compatible-mode"), eq("sk-direct")
        );
    }
}
