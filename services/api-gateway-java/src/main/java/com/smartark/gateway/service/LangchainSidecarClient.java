package com.smartark.gateway.service;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.dto.LangchainContextBuildRequest;
import com.smartark.gateway.dto.LangchainContextBuildResult;
import com.smartark.gateway.dto.LangchainHealthResult;
import com.smartark.gateway.dto.LangchainMemoryReadRequest;
import com.smartark.gateway.dto.LangchainMemoryReadResult;
import com.smartark.gateway.dto.LangchainMemoryWriteRequest;
import com.smartark.gateway.dto.LangchainMemoryWriteResult;
import com.smartark.gateway.dto.LangchainQualityEvaluateRequest;
import com.smartark.gateway.dto.LangchainQualityEvaluateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Service
public class LangchainSidecarClient {
    private static final Logger logger = LoggerFactory.getLogger(LangchainSidecarClient.class);
    private static final int MAX_RETRIES = 1;
    private static final String HEADER_API_VERSION = "X-SmartArk-Sidecar-Api-Version";

    private final RestClient restClient;
    private final boolean langchainEnabled;
    private final String sidecarBaseUrl;
    private final int timeoutMs;
    private final String sidecarApiVersion;

    public LangchainSidecarClient(
            @Value("${smartark.langchain.enabled:false}") boolean langchainEnabled,
            @Value("${smartark.langchain.sidecar.base-url:http://localhost:18080}") String sidecarBaseUrl,
            @Value("${smartark.langchain.sidecar.timeout-ms:3000}") int timeoutMs,
            @Value("${smartark.langchain.sidecar.api-version:v1}") String sidecarApiVersion
    ) {
        this.langchainEnabled = langchainEnabled;
        this.sidecarBaseUrl = sidecarBaseUrl == null ? "" : sidecarBaseUrl.trim();
        this.timeoutMs = Math.max(timeoutMs, 500);
        this.sidecarApiVersion = sidecarApiVersion == null || sidecarApiVersion.isBlank() ? "v1" : sidecarApiVersion.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(this.timeoutMs);
        factory.setReadTimeout(this.timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public LangchainHealthResult health() {
        if (!langchainEnabled) {
            return new LangchainHealthResult("disabled", "langchain.enabled=false");
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainHealthResult result = doGetWithRetry("/health", LangchainHealthResult.class, "health");
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.info("sidecar_call endpoint=/health status=ok durationMs={}", durationMs);
            return result;
        } catch (BusinessException e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.warn("sidecar_call endpoint=/health status=failed code={} durationMs={}", e.getCode(), durationMs);
            throw e;
        }
    }

    public LangchainContextBuildResult buildContext(LangchainContextBuildRequest request) {
        if (!langchainEnabled) {
            return new LangchainContextBuildResult("", List.of(), 0);
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainContextBuildResult result = doPostWithRetry("/context/build", request, LangchainContextBuildResult.class, "context_build");
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.info("sidecar_call endpoint=/context/build status=ok durationMs={}", durationMs);
            return result;
        } catch (BusinessException e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.warn("sidecar_call endpoint=/context/build status=failed code={} durationMs={}", e.getCode(), durationMs);
            throw e;
        }
    }

    public LangchainQualityEvaluateResult evaluateQuality(LangchainQualityEvaluateRequest request) {
        if (!langchainEnabled) {
            return new LangchainQualityEvaluateResult(true, List.of(), List.of(), null);
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainQualityEvaluateResult result = doPostWithRetry("/quality/evaluate", request, LangchainQualityEvaluateResult.class, "quality_evaluate");
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.info("sidecar_call endpoint=/quality/evaluate status=ok durationMs={}", durationMs);
            return result;
        } catch (BusinessException e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.warn("sidecar_call endpoint=/quality/evaluate status=failed code={} durationMs={}", e.getCode(), durationMs);
            throw e;
        }
    }

    public LangchainMemoryReadResult readMemory(LangchainMemoryReadRequest request) {
        if (!langchainEnabled) {
            return new LangchainMemoryReadResult(List.of());
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainMemoryReadResult result = doPostWithRetry("/memory/read", request, LangchainMemoryReadResult.class, "memory_read");
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.info("sidecar_call endpoint=/memory/read status=ok durationMs={}", durationMs);
            return result == null ? new LangchainMemoryReadResult(List.of()) : result;
        } catch (BusinessException e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.warn("sidecar_call endpoint=/memory/read status=failed code={} durationMs={}", e.getCode(), durationMs);
            throw e;
        }
    }

    public LangchainMemoryWriteResult writeMemory(LangchainMemoryWriteRequest request) {
        if (!langchainEnabled) {
            return new LangchainMemoryWriteResult(false, null);
        }
        long startedAt = System.currentTimeMillis();
        try {
            LangchainMemoryWriteResult result = doPostWithRetry("/memory/write", request, LangchainMemoryWriteResult.class, "memory_write");
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.info("sidecar_call endpoint=/memory/write status=ok durationMs={}", durationMs);
            return result == null ? new LangchainMemoryWriteResult(false, null) : result;
        } catch (BusinessException e) {
            long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
            logger.warn("sidecar_call endpoint=/memory/write status=failed code={} durationMs={}", e.getCode(), durationMs);
            throw e;
        }
    }

    private <T> T doGetWithRetry(String path, Class<T> responseType, String operation) {
        BusinessException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return restClient.get()
                        .uri(buildUrl(path))
                        .header(HEADER_API_VERSION, sidecarApiVersion)
                        .retrieve()
                        .body(responseType);
            } catch (ResourceAccessException | RestClientResponseException e) {
                lastError = toBusinessException(operation, e);
                if (attempt < MAX_RETRIES) {
                    logger.warn("sidecar_retry operation={} attempt={}", operation, attempt + 1);
                }
            } catch (Exception e) {
                throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " failed: " + e.getMessage());
            }
        }
        throw lastError == null
                ? new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " failed")
                : lastError;
    }

    private <T> T doPostWithRetry(String path, Object payload, Class<T> responseType, String operation) {
        BusinessException lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return restClient.post()
                        .uri(buildUrl(path))
                        .header(HEADER_API_VERSION, sidecarApiVersion)
                        .body(payload)
                        .retrieve()
                        .body(responseType);
            } catch (ResourceAccessException | RestClientResponseException e) {
                lastError = toBusinessException(operation, e);
                if (attempt < MAX_RETRIES) {
                    logger.warn("sidecar_retry operation={} attempt={}", operation, attempt + 1);
                }
            } catch (Exception e) {
                throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " failed: " + e.getMessage());
            }
        }
        throw lastError == null
                ? new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " failed")
                : lastError;
    }

    private String buildUrl(String path) {
        if (sidecarBaseUrl.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar base-url is empty");
        }
        if (sidecarBaseUrl.endsWith("/")) {
            return sidecarBaseUrl.substring(0, sidecarBaseUrl.length() - 1) + path;
        }
        return sidecarBaseUrl + path;
    }

    private BusinessException toBusinessException(String operation, Exception error) {
        if (error instanceof ResourceAccessException) {
            return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " timeout");
        }
        if (error instanceof RestClientResponseException responseException) {
            return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " http status=" + responseException.getStatusCode());
        }
        return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "sidecar " + operation + " failed");
    }
}
