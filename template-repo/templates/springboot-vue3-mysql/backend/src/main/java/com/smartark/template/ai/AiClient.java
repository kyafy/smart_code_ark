package com.smartark.template.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

/**
 * Unified LLM client supporting OpenAI-compatible APIs.
 *
 * Usage in any service:
 *   String result = aiClient.chat("Analyze this resume", resumeText);
 *   aiClient.chatStream("Optimize this", resumeText, chunk -> emitter.send(chunk));
 *
 * The client exposes both a simple single-turn helper and a lower-level
 * message-list overload. Generated business code can start simple and move to
 * multi-step orchestration without introducing a second AI abstraction.
 */
@Component
public class AiClient {

    private final AiConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiClient(AiConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Single-turn chat helper. */
    public String chat(String systemPrompt, String userMessage) {
        return chat(List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)
        ));
    }

    /** Multi-turn chat with full message list. */
    public String chat(List<Message> messages) {
        try {
            // Build the payload in one place so sync and streaming paths use the
            // same request shape and stay easy to reason about.
            String body = buildBody(messages, false);
            HttpResponse<String> response = httpClient.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("AI API error " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return root.at("/choices/0/message/content").asText("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AI chat failed: " + e.getMessage(), e);
        }
    }

    /** Streaming chat helper. */
    public void chatStream(String systemPrompt, String userMessage, Consumer<String> onChunk) {
        chatStream(List.of(
                new Message("system", systemPrompt),
                new Message("user", userMessage)
        ), onChunk);
    }

    /** Streaming chat with full message list. */
    public void chatStream(List<Message> messages, Consumer<String> onChunk) {
        try {
            String body = buildBody(messages, true);
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    buildRequest(body),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() != 200) {
                throw new RuntimeException("AI API error " + response.statusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Streaming providers emit one SSE line at a time. We only
                    // forward meaningful text deltas to the caller.
                    if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                        JsonNode chunk = objectMapper.readTree(line.substring(6));
                        String content = chunk.at("/choices/0/delta/content").asText("");
                        if (!content.isEmpty()) {
                            onChunk.accept(content);
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AI stream failed: " + e.getMessage(), e);
        }
    }

    private String buildBody(List<Message> messages, boolean stream) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", config.getModel());
            root.put("stream", stream);
            root.put("temperature", config.getTemperature());
            if (config.getMaxTokens() > 0) {
                root.put("max_tokens", config.getMaxTokens());
            }
            // Preserve message order exactly so callers can model multi-step
            // prompt flows without the client reinterpreting conversation state.
            ArrayNode messageArray = root.putArray("messages");
            for (Message message : messages) {
                messageArray.addObject()
                        .put("role", message.role())
                        .put("content", message.content());
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build AI request body", e);
        }
    }

    private HttpRequest buildRequest(String body) {
        // The template keeps provider integration OpenAI-compatible so switching
        // from one gateway to another only requires config changes.
        String url = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    public record Message(String role, String content) {}
}
