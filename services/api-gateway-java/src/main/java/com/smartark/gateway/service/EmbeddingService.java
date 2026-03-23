package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int DEFAULT_BATCH_SIZE = 16;
    private static final int DASHSCOPE_NATIVE_BATCH_SIZE = 10;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String embeddingBaseUrl;
    private final String apiKey;
    private final String embeddingModel;
    private final int embeddingDimension;

    public EmbeddingService(
            ObjectMapper objectMapper,
            @Value("${smartark.model.base-url:}") String baseUrl,
            @Value("${smartark.rag.embedding-base-url:}") String embeddingBaseUrl,
            @Value("${smartark.model.api-key:}") String apiKey,
            @Value("${smartark.rag.embedding-model:text-embedding-v3}") String embeddingModel,
            @Value("${smartark.rag.embedding-dimension:1024}") int embeddingDimension
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.embeddingBaseUrl = embeddingBaseUrl == null ? "" : embeddingBaseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.restClient = RestClient.builder().build();
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        if (resolveEmbeddingBaseUrl().isEmpty() || apiKey.isEmpty()) {
            logger.warn("Embedding service not configured, returning mock vectors");
            return texts.stream()
                    .map(t -> new float[embeddingDimension])
                    .toList();
        }

        String effectiveBaseUrl = resolveEmbeddingBaseUrl();
        int batchSize = resolveBatchSize(effectiveBaseUrl);
        List<float[]> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<float[]> batchVectors = embedBatch(batch);
            allVectors.addAll(batchVectors);
        }
        return allVectors;
    }

    private List<float[]> embedBatch(List<String> texts) {
        try {
            String effectiveBaseUrl = resolveEmbeddingBaseUrl();
            String embeddingUri = buildEmbeddingUri(effectiveBaseUrl);
            Map<String, Object> requestBody = buildRequestBody(effectiveBaseUrl, texts);
            String responseJson = restClient.post()
                    .uri(embeddingUri)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(String.class);
            return parseVectors(responseJson);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Embedding API call failed", e);
            throw new BusinessException(ErrorCodes.RAG_EMBEDDING_FAILED, "Embedding失败: " + e.getMessage());
        }
    }

    private String resolveEmbeddingBaseUrl() {
        if (!embeddingBaseUrl.isEmpty()) {
            return embeddingBaseUrl;
        }
        return baseUrl;
    }

    private String buildEmbeddingUri(String effectiveBaseUrl) {
        if (isDashScopeNative(effectiveBaseUrl)) {
            if (effectiveBaseUrl.endsWith("/api/v1/services/embeddings/text-embedding/text-embedding")) {
                return effectiveBaseUrl;
            }
            return trimTrailingSlash(effectiveBaseUrl) + "/api/v1/services/embeddings/text-embedding/text-embedding";
        }
        if (effectiveBaseUrl.endsWith("/v1")) {
            return effectiveBaseUrl + "/embeddings";
        }
        return trimTrailingSlash(effectiveBaseUrl) + "/v1/embeddings";
    }

    private Map<String, Object> buildRequestBody(String effectiveBaseUrl, List<String> texts) {
        if (isDashScopeNative(effectiveBaseUrl)) {
            return Map.of(
                    "model", embeddingModel,
                    "input", Map.of("texts", texts),
                    "parameters", Map.of("dimension", embeddingDimension)
            );
        }
        return Map.of(
                "model", embeddingModel,
                "input", texts,
                "dimensions", embeddingDimension
        );
    }

    private List<float[]> parseVectors(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode dataArray = root.path("data");
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            dataArray = root.path("output").path("embeddings");
        }
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            throw new BusinessException(ErrorCodes.RAG_EMBEDDING_FAILED, "Embedding返回结果为空");
        }
        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : dataArray) {
            JsonNode embeddingNode = item.path("embedding");
            float[] vector = new float[embeddingNode.size()];
            for (int j = 0; j < embeddingNode.size(); j++) {
                vector[j] = (float) embeddingNode.get(j).asDouble();
            }
            if (vector.length != embeddingDimension) {
                throw new BusinessException(ErrorCodes.RAG_EMBEDDING_DIMENSION_MISMATCH,
                        "Embedding dimension mismatch: expected " + embeddingDimension + ", got " + vector.length);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private boolean isDashScopeNative(String effectiveBaseUrl) {
        return effectiveBaseUrl.contains("dashscope.aliyuncs.com") && !effectiveBaseUrl.contains("compatible-mode");
    }

    private String trimTrailingSlash(String input) {
        if (input.endsWith("/")) {
            return input.substring(0, input.length() - 1);
        }
        return input;
    }

    private int resolveBatchSize(String effectiveBaseUrl) {
        if (isDashScopeNative(effectiveBaseUrl)) {
            return DASHSCOPE_NATIVE_BATCH_SIZE;
        }
        return DEFAULT_BATCH_SIZE;
    }
}
