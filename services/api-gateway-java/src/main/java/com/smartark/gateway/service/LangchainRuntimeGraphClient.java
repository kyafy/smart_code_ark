package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.dto.LangchainGraphRunRequest;
import com.smartark.gateway.dto.LangchainGraphRunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;

/**
 * Dedicated client for langchain-runtime graph APIs.
 * This keeps graph orchestration invocation isolated from sidecar compatibility APIs.
 */
@Service
public class LangchainRuntimeGraphClient {
    private static final Logger logger = LoggerFactory.getLogger(LangchainRuntimeGraphClient.class);
    private static final int MAX_RETRIES = 1;

    private final RestClient restClient;
    private final String runtimeBaseUrl;

    public LangchainRuntimeGraphClient(
            @Value("${smartark.langchain.runtime.base-url:http://localhost:18080}") String runtimeBaseUrl,
            @Value("${smartark.langchain.runtime.timeout-ms:45000}") int timeoutMs
    ) {
        this.runtimeBaseUrl = runtimeBaseUrl == null ? "" : runtimeBaseUrl.trim();
        int effectiveTimeout = Math.max(1000, timeoutMs);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(effectiveTimeout);
        factory.setReadTimeout(effectiveTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public LangchainGraphRunResult runPaperGraph(String taskId,
                                                 String projectId,
                                                 Long userId,
                                                 Map<String, Object> input) {
        return invokeGraph("paper", "/v1/graph/paper/run", taskId, projectId, userId, input);
    }

    public LangchainGraphRunResult runCodegenGraph(String taskId,
                                                   String projectId,
                                                   Long userId,
                                                   Map<String, Object> input) {
        return invokeGraph("codegen", "/v1/graph/codegen/run", taskId, projectId, userId, input);
    }

    private LangchainGraphRunResult invokeGraph(String graphName,
                                                String endpointPath,
                                                String taskId,
                                                String projectId,
                                                Long userId,
                                                Map<String, Object> input) {
        HashMap<String, Object> normalizedInput = new HashMap<>();
        if (input != null) {
            normalizedInput.putAll(input);
        }
        LangchainGraphRunRequest payload = new LangchainGraphRunRequest(
                taskId,
                projectId,
                userId == null ? null : String.valueOf(userId),
                normalizedInput
        );
        long startedAt = System.currentTimeMillis();
        BusinessException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                LangchainGraphRunResult result = restClient.post()
                        .uri(buildUrl(endpointPath))
                        .body(payload)
                        .retrieve()
                        .body(LangchainGraphRunResult.class);
                long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
                logger.info("runtime_graph_call graph={} endpoint={} status=ok durationMs={}",
                        graphName, endpointPath, durationMs);
                if (result == null) {
                    return new LangchainGraphRunResult(null, taskId, graphName, "failed", Map.of());
                }
                return result;
            } catch (ResourceAccessException e) {
                lastError = new BusinessException(ErrorCodes.MODEL_UPSTREAM_TIMEOUT, "runtime " + graphName + " graph timeout");
            } catch (RestClientResponseException e) {
                lastError = mapRuntimeHttpException(graphName, e);
            } catch (BusinessException e) {
                lastError = e;
            } catch (Exception e) {
                lastError = new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "runtime " + graphName + " graph failed: " + e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                logger.warn("runtime_graph_retry graph={} endpoint={} attempt={}", graphName, endpointPath, attempt + 1);
            }
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        logger.warn("runtime_graph_call graph={} endpoint={} status=failed durationMs={} errorCode={}",
                graphName, endpointPath, durationMs, lastError == null ? ErrorCodes.MODEL_SERVICE_ERROR : lastError.getCode());
        throw lastError == null
                ? new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "runtime " + graphName + " graph failed")
                : lastError;
    }

    private String buildUrl(String path) {
        if (runtimeBaseUrl.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_CONFIG_MISSING, "runtime base-url is empty");
        }
        String normalized = runtimeBaseUrl.endsWith("/")
                ? runtimeBaseUrl.substring(0, runtimeBaseUrl.length() - 1)
                : runtimeBaseUrl;
        if (normalized.endsWith("/v1") && path.startsWith("/v1/")) {
            return normalized + path.substring(3);
        }
        return normalized + path;
    }

    private BusinessException mapRuntimeHttpException(String graphName, RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        String detail = ex.getResponseBodyAsString();
        String message = "runtime " + graphName + " graph http status=" + status
                + (detail == null || detail.isBlank() ? "" : ", body=" + trim(detail, 280));
        if (status == 400 || status == 422) {
            return new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, message);
        }
        if (status == 401 || status == 403) {
            return new BusinessException(ErrorCodes.MODEL_AUTH_FAILED, message);
        }
        if (status == 429) {
            return new BusinessException(ErrorCodes.MODEL_RATE_LIMITED, message);
        }
        if (status >= 500) {
            return new BusinessException(ErrorCodes.MODEL_UPSTREAM_UNAVAILABLE, message);
        }
        return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, message);
    }

    private String trim(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace("\n", " ").replace("\r", " ");
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen);
    }
}
