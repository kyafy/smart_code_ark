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
    private static final int BATCH_SIZE = 16;

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final String embeddingModel;
    private final int embeddingDimension;

    public EmbeddingService(
            ObjectMapper objectMapper,
            @Value("${smartark.model.base-url:}") String baseUrl,
            @Value("${smartark.model.api-key:}") String apiKey,
            @Value("${smartark.rag.embedding-model:text-embedding-v3}") String embeddingModel,
            @Value("${smartark.rag.embedding-dimension:1024}") int embeddingDimension
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
        this.restClient = RestClient.builder().build();
    }

    public List<float[]> embed(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            logger.warn("Embedding service not configured, returning mock vectors");
            return texts.stream()
                    .map(t -> new float[embeddingDimension])
                    .toList();
        }

        List<float[]> allVectors = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size()));
            List<float[]> batchVectors = embedBatch(batch);
            allVectors.addAll(batchVectors);
        }
        return allVectors;
    }

    private List<float[]> embedBatch(List<String> texts) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", embeddingModel,
                    "input", texts,
                    "dimensions", embeddingDimension
            );

            String responseJson = restClient.post()
                    .uri(baseUrl + "/v1/embeddings")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(objectMapper.writeValueAsString(requestBody))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode dataArray = root.path("data");
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
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Embedding API call failed", e);
            throw new BusinessException(ErrorCodes.RAG_EMBEDDING_FAILED, "Embedding失败: " + e.getMessage());
        }
    }
}
