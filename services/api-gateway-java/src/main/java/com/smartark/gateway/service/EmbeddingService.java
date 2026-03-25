package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.service.modelgateway.ModelGatewayEndpoint;
import com.smartark.gateway.service.modelgateway.ModelGatewayInvocation;
import com.smartark.gateway.service.modelgateway.ModelGatewayResult;
import com.smartark.gateway.service.modelgateway.OpenAiCompatibleModelGatewayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int DEFAULT_BATCH_SIZE = 10;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String embeddingBaseUrl;
    private final String apiKey;
    private final String embeddingModel;
    private final int embeddingDimension;
    private final ModelRouterService modelRouterService;
    private final OpenAiCompatibleModelGatewayService modelGatewayService;

    private record EmbeddingConnection(String modelName, String baseUrl, String apiKey, String source) {
    }

    public EmbeddingService(
            ObjectMapper objectMapper,
            ModelRouterService modelRouterService,
            OpenAiCompatibleModelGatewayService modelGatewayService,
            @Value("${smartark.model.base-url:}") String baseUrl,
            @Value("${smartark.rag.embedding-base-url:}") String embeddingBaseUrl,
            @Value("${smartark.model.api-key:}") String apiKey,
            @Value("${smartark.rag.embedding-model:text-embedding-v3}") String embeddingModel,
            @Value("${smartark.rag.embedding-dimension:1024}") int embeddingDimension
    ) {
        this.objectMapper = objectMapper;
        this.modelRouterService = modelRouterService;
        this.modelGatewayService = modelGatewayService;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.embeddingBaseUrl = embeddingBaseUrl == null ? "" : embeddingBaseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        EmbeddingConnection connection = resolveEmbeddingConnection();
        if (connection == null) {
            logger.warn("Embedding service not configured, returning mock vectors");
            return texts.stream()
                    .map(t -> new float[embeddingDimension])
                    .toList();
        }

        int batchSize = resolveBatchSize();
        List<float[]> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(i + batchSize, texts.size()));
            List<float[]> batchVectors = embedBatch(batch, connection);
            allVectors.addAll(batchVectors);
        }
        return allVectors;
    }

    private List<float[]> embedBatch(List<String> texts, EmbeddingConnection connection) {
        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", connection.modelName());
            requestBody.put("input", texts.size() == 1 ? texts.get(0) : texts);
            requestBody.put("encoding_format", "float");
            if (embeddingDimension > 0) {
                requestBody.put("dimensions", embeddingDimension);
            }
            ModelGatewayResult result = modelGatewayService.invoke(new ModelGatewayInvocation(
                    ModelGatewayEndpoint.EMBEDDINGS,
                    "dashscope",
                    connection.modelName(),
                    toCompatibleBaseUrl(connection.baseUrl()),
                    connection.apiKey(),
                    requestBody,
                    "rag_embedding",
                    null));
            return parseVectors(result.body());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Embedding API call failed", e);
            throw new BusinessException(ErrorCodes.RAG_EMBEDDING_FAILED, "Embedding澶辫触: " + e.getMessage());
        }
    }

    private EmbeddingConnection resolveEmbeddingConnection() {
        String resolvedEmbeddingModel = modelRouterService.resolve("embedding");
        if (resolvedEmbeddingModel != null && !resolvedEmbeddingModel.isBlank()) {
            var fromRegistry = modelRouterService.resolveConnection(resolvedEmbeddingModel);
            if (fromRegistry.isPresent()) {
                var c = fromRegistry.get();
                return new EmbeddingConnection(resolvedEmbeddingModel, c.baseUrl(), c.apiKey(), "registry");
            }
        }
        String fallbackBase = !embeddingBaseUrl.isEmpty() ? embeddingBaseUrl : baseUrl;
        if (!fallbackBase.isEmpty() && !apiKey.isEmpty()) {
            String model = (resolvedEmbeddingModel == null || resolvedEmbeddingModel.isBlank()) ? embeddingModel : resolvedEmbeddingModel;
            return new EmbeddingConnection(model, fallbackBase, apiKey, "env");
        }
        return null;
    }

    private List<float[]> parseVectors(String responseJson) throws Exception {
        JsonNode root = objectMapper.readTree(responseJson);
        JsonNode dataArray = root.path("data");
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            dataArray = root.path("output").path("embeddings");
        }
        if (!dataArray.isArray() || dataArray.isEmpty()) {
            throw new BusinessException(ErrorCodes.RAG_EMBEDDING_FAILED, "Embedding杩斿洖缁撴灉涓虹┖");
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

    private int resolveBatchSize() {
        return DEFAULT_BATCH_SIZE;
    }

    private String toCompatibleBaseUrl(String effectiveBaseUrl) {
        String trimmed = effectiveBaseUrl == null ? "" : effectiveBaseUrl.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        if (trimmed.contains("/compatible-mode")) {
            return trimmed;
        }
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains("dashscope.aliyuncs.com")) {
            return trimmed + "/compatible-mode";
        }
        return trimmed;
    }
}
