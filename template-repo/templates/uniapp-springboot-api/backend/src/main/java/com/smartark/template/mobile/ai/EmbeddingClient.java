package com.smartark.template.mobile.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Embedding client for vector operations.
 *
 * Usage:
 *   float[] vec = embeddingClient.embed("Hello world");
 *   List<float[]> vecs = embeddingClient.embedBatch(List.of("text1", "text2"));
 */
@Component
public class EmbeddingClient {

    private final AiConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient(AiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Embed a single text. */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** Embed multiple texts in one API call. */
    public List<float[]> embedBatch(List<String> texts) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.getEmbeddingModel());
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);

            String url = config.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Embedding API error " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            List<float[]> results = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                JsonNode embNode = item.path("embedding");
                float[] vec = new float[embNode.size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) embNode.get(i).asDouble();
                }
                results.add(vec);
            }
            return results;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Embedding failed: " + e.getMessage(), e);
        }
    }
}
