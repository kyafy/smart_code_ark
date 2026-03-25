package com.smartark.gateway.service.modelgateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class OpenAiCompatibleModelGatewayService {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleModelGatewayService.class);

    private final ObjectMapper objectMapper;
    private final ModelGatewayAuditService auditService;
    private final int defaultTimeoutMs;
    private final int maxRetries;
    private final boolean responseValidationEnabled;
    private final ConcurrentHashMap<String, OpenAIClient> clientCache = new ConcurrentHashMap<>();

    public OpenAiCompatibleModelGatewayService(
            ObjectMapper objectMapper,
            ModelGatewayAuditService auditService,
            @Value("${smartark.model.gateway.default-timeout-ms:45000}") int defaultTimeoutMs,
            @Value("${smartark.model.gateway.max-retries:2}") int maxRetries,
            @Value("${smartark.model.gateway.response-validation-enabled:true}") boolean responseValidationEnabled) {
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.defaultTimeoutMs = Math.max(1000, defaultTimeoutMs);
        this.maxRetries = Math.max(0, maxRetries);
        this.responseValidationEnabled = responseValidationEnabled;
    }

    public ModelGatewayResult invoke(ModelGatewayInvocation invocation) {
        validateInvocation(invocation);
        String requestBody = serializeQuietly(invocation.payload());
        long start = System.currentTimeMillis();
        try {
            ModelGatewayResult result = switch (invocation.endpoint()) {
                case CHAT_COMPLETIONS -> invokeChatCompletion(invocation);
                case EMBEDDINGS -> invokeEmbeddings(invocation);
            };
            auditService.save(invocation, result, System.currentTimeMillis() - start, true, null, null, requestBody);
            return result;
        } catch (BusinessException ex) {
            auditService.save(invocation, null, System.currentTimeMillis() - start, false,
                    String.valueOf(ex.getCode()), ex.getMessage(), requestBody);
            throw ex;
        } catch (RuntimeException ex) {
            auditService.save(invocation, null, System.currentTimeMillis() - start, false,
                    String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), ex.getMessage(), requestBody);
            throw ex;
        }
    }

    public ModelGatewayResult invokeStreaming(ModelGatewayInvocation invocation, Consumer<String> onContent) {
        validateInvocation(invocation);
        if (invocation.endpoint() != ModelGatewayEndpoint.CHAT_COMPLETIONS) {
            throw new BusinessException(ErrorCodes.MODEL_UNSUPPORTED_OPERATION, "Only chat.completions supports streaming");
        }
        if (!invocation.stream()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "Streaming invocation requires payload.stream=true");
        }
        String requestBody = serializeQuietly(invocation.payload());
        long start = System.currentTimeMillis();
        try {
            ModelGatewayResult result = invokeChatCompletionStreaming(invocation, onContent);
            auditService.save(invocation, result, System.currentTimeMillis() - start, true, null, null, requestBody);
            return result;
        } catch (BusinessException ex) {
            auditService.save(invocation, null, System.currentTimeMillis() - start, false,
                    String.valueOf(ex.getCode()), ex.getMessage(), requestBody);
            throw ex;
        } catch (RuntimeException ex) {
            auditService.save(invocation, null, System.currentTimeMillis() - start, false,
                    String.valueOf(ErrorCodes.MODEL_SERVICE_ERROR), ex.getMessage(), requestBody);
            throw ex;
        }
    }

    public String normalizeBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_CONFIG_MISSING, "模型网关配置缺失: baseUrl 不能为空");
        }
        String normalized = rawBaseUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            normalized = normalized.substring(0, normalized.length() - "/chat/completions".length());
        } else if (normalized.endsWith("/embeddings")) {
            normalized = normalized.substring(0, normalized.length() - "/embeddings".length());
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private ModelGatewayResult invokeChatCompletion(ModelGatewayInvocation invocation) {
        if (invocation.stream()) {
            throw new BusinessException(ErrorCodes.MODEL_UNSUPPORTED_OPERATION, "当前网关 raw API 暂不支持通过同步接口处理 stream=true");
        }
        ChatCompletionCreateParams params = buildChatParams(invocation.payload());
        OpenAIClient client = resolveClient(invocation.baseUrl(), invocation.apiKey());
        HttpResponseFor<ChatCompletion> response = executeChat(client, params, invocation.timeoutMs());
        ChatCompletion chatCompletion = response.parse();
        JsonNode body = objectMapper.valueToTree(chatCompletion);
        String bodyJson = serializeQuietly(body);
        return new ModelGatewayResult(
                bodyJson,
                response.requestId().orElse(null),
                response.statusCode(),
                readInt(body, "/usage/prompt_tokens"),
                readInt(body, "/usage/completion_tokens"),
                readInt(body, "/usage/total_tokens"));
    }

    private ModelGatewayResult invokeEmbeddings(ModelGatewayInvocation invocation) {
        EmbeddingCreateParams params = buildEmbeddingParams(invocation.payload());
        OpenAIClient client = resolveClient(invocation.baseUrl(), invocation.apiKey());
        HttpResponseFor<CreateEmbeddingResponse> response = executeEmbeddings(client, params, invocation.timeoutMs());
        CreateEmbeddingResponse embeddingResponse = response.parse();
        JsonNode body = objectMapper.valueToTree(embeddingResponse);
        String bodyJson = serializeQuietly(body);
        return new ModelGatewayResult(
                bodyJson,
                response.requestId().orElse(null),
                response.statusCode(),
                readInt(body, "/usage/prompt_tokens"),
                null,
                readInt(body, "/usage/total_tokens"));
    }

    private ModelGatewayResult invokeChatCompletionStreaming(ModelGatewayInvocation invocation, Consumer<String> onContent) {
        ChatCompletionCreateParams params = buildChatParams(invocation.payload());
        OpenAIClient client = resolveClient(invocation.baseUrl(), invocation.apiKey());
        HttpResponseFor<StreamResponse<ChatCompletionChunk>> response = executeChatStreaming(client, params, invocation.timeoutMs());
        StringBuilder contentBuilder = new StringBuilder();
        String[] finishReason = new String[1];
        CompletionUsage[] usage = new CompletionUsage[1];
        try (StreamResponse<ChatCompletionChunk> streamResponse = response.parse()) {
            streamResponse.stream().forEach(chunk -> {
                if (chunk.usage().isPresent()) {
                    usage[0] = chunk.usage().get();
                }
                for (ChatCompletionChunk.Choice choice : chunk.choices()) {
                    String delta = choice.delta().content().orElse("");
                    if (!delta.isEmpty()) {
                        contentBuilder.append(delta);
                        if (onContent != null) {
                            onContent.accept(delta);
                        }
                    }
                    if (finishReason[0] == null && choice.finishReason().isPresent()) {
                        finishReason[0] = choice.finishReason().get().asString();
                    }
                }
            });
        } catch (RuntimeException ex) {
            throw mapException(ex);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, buildErrorMessage("Stream read failed", ex));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", contentBuilder.toString());
        body.put("finish_reason", finishReason[0]);
        if (usage[0] != null) {
            body.put("usage", Map.of(
                    "prompt_tokens", usage[0].promptTokens(),
                    "completion_tokens", usage[0].completionTokens(),
                    "total_tokens", usage[0].totalTokens()
            ));
        }
        return new ModelGatewayResult(
                serializeQuietly(body),
                response.requestId().orElse(null),
                response.statusCode(),
                usage[0] == null ? null : toInt(usage[0].promptTokens()),
                usage[0] == null ? null : toInt(usage[0].completionTokens()),
                usage[0] == null ? null : toInt(usage[0].totalTokens())
        );
    }

    private ChatCompletionCreateParams buildChatParams(Map<String, Object> payload) {
        Object model = payload.get("model");
        Object messages = payload.get("messages");
        if (!(model instanceof String modelName) || modelName.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: chat.completions 缺少 model");
        }
        if (messages == null) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: chat.completions 缺少 messages");
        }
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(modelName)
                .messages(JsonValue.from(messages));
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if ("model".equals(key) || "messages".equals(key)) {
                continue;
            }
            builder.putAdditionalBodyProperty(key, JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private EmbeddingCreateParams buildEmbeddingParams(Map<String, Object> payload) {
        Object model = payload.get("model");
        Object input = payload.get("input");
        if (!(model instanceof String modelName) || modelName.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: embeddings 缺少 model");
        }
        if (input == null) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: embeddings 缺少 input");
        }
        EmbeddingCreateParams.Builder builder = EmbeddingCreateParams.builder()
                .model(modelName)
                .input(JsonValue.from(input));
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if ("model".equals(key) || "input".equals(key)) {
                continue;
            }
            builder.putAdditionalBodyProperty(key, JsonValue.from(entry.getValue()));
        }
        return builder.build();
    }

    private HttpResponseFor<ChatCompletion> executeChat(OpenAIClient client,
                                                        ChatCompletionCreateParams params,
                                                        Integer timeoutMs) {
        try {
            return client.chat().completions().withRawResponse().create(params, requestOptions(timeoutMs));
        } catch (RuntimeException ex) {
            throw mapException(ex);
        }
    }

    private HttpResponseFor<StreamResponse<ChatCompletionChunk>> executeChatStreaming(OpenAIClient client,
                                                                                       ChatCompletionCreateParams params,
                                                                                       Integer timeoutMs) {
        try {
            return client.chat().completions().withRawResponse().createStreaming(params, requestOptions(timeoutMs));
        } catch (RuntimeException ex) {
            throw mapException(ex);
        }
    }

    private HttpResponseFor<CreateEmbeddingResponse> executeEmbeddings(OpenAIClient client,
                                                                       EmbeddingCreateParams params,
                                                                       Integer timeoutMs) {
        try {
            return client.embeddings().withRawResponse().create(params, requestOptions(timeoutMs));
        } catch (RuntimeException ex) {
            throw mapException(ex);
        }
    }

    private OpenAIClient resolveClient(String rawBaseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_CONFIG_MISSING, "模型网关配置缺失: apiKey 不能为空");
        }
        String normalizedBaseUrl = normalizeBaseUrl(rawBaseUrl);
        String cacheKey = normalizedBaseUrl + "::" + apiKey.trim();
        return clientCache.computeIfAbsent(cacheKey, key -> OpenAIOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .baseUrl(normalizedBaseUrl)
                .maxRetries(maxRetries)
                .responseValidation(responseValidationEnabled)
                .timeout(Duration.ofMillis(defaultTimeoutMs))
                .build());
    }

    private RequestOptions requestOptions(Integer timeoutMs) {
        int effectiveTimeoutMs = timeoutMs != null && timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        return RequestOptions.builder()
                .timeout(Duration.ofMillis(effectiveTimeoutMs))
                .responseValidation(responseValidationEnabled)
                .build();
    }

    private void validateInvocation(ModelGatewayInvocation invocation) {
        if (invocation == null) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: invocation 不能为空");
        }
        if (invocation.endpoint() == null) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: endpoint 不能为空");
        }
        if (invocation.payload() == null || invocation.payload().isEmpty()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: payload 不能为空");
        }
    }

    private BusinessException mapException(RuntimeException ex) {
        if (ex instanceof BusinessException businessException) {
            return businessException;
        }
        if (ex instanceof UnauthorizedException || ex instanceof PermissionDeniedException) {
            return new BusinessException(ErrorCodes.MODEL_AUTH_FAILED, buildErrorMessage("模型网关鉴权失败", ex));
        }
        if (ex instanceof RateLimitException) {
            return new BusinessException(ErrorCodes.MODEL_RATE_LIMITED, buildErrorMessage("模型网关触发上游限流", ex));
        }
        if (ex instanceof BadRequestException) {
            return new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, buildErrorMessage("模型网关请求参数不符合官方协议", ex));
        }
        if (ex instanceof OpenAIIoException) {
            return new BusinessException(ErrorCodes.MODEL_UPSTREAM_TIMEOUT, buildErrorMessage("模型网关访问上游超时或网络异常", ex));
        }
        if (ex instanceof InternalServerException) {
            return new BusinessException(ErrorCodes.MODEL_UPSTREAM_UNAVAILABLE, buildErrorMessage("模型网关上游服务不可用", ex));
        }
        if (ex instanceof OpenAIInvalidDataException) {
            return new BusinessException(ErrorCodes.MODEL_OUTPUT_EMPTY, buildErrorMessage("模型网关无法解析上游返回", ex));
        }
        if (ex instanceof OpenAIServiceException serviceException) {
            return mapServiceException(serviceException);
        }
        return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, buildErrorMessage("模型网关调用失败", ex));
    }

    private BusinessException mapServiceException(OpenAIServiceException ex) {
        int statusCode = ex.statusCode();
        if (statusCode == 400 || statusCode == 422) {
            return new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, buildErrorMessage("模型网关请求参数不符合官方协议", ex));
        }
        if (statusCode == 401 || statusCode == 403) {
            return new BusinessException(ErrorCodes.MODEL_AUTH_FAILED, buildErrorMessage("模型网关鉴权失败", ex));
        }
        if (statusCode == 429) {
            return new BusinessException(ErrorCodes.MODEL_RATE_LIMITED, buildErrorMessage("模型网关触发上游限流", ex));
        }
        if (statusCode >= 500) {
            return new BusinessException(ErrorCodes.MODEL_UPSTREAM_UNAVAILABLE, buildErrorMessage("模型网关上游服务不可用", ex));
        }
        return new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, buildErrorMessage("模型网关调用失败", ex));
    }

    private String buildErrorMessage(String prefix, RuntimeException ex) {
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = ex.getClass().getSimpleName();
        }
        return prefix + ": " + detail;
    }

    private String buildErrorMessage(String prefix, Exception ex) {
        String detail = ex.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = ex.getClass().getSimpleName();
        }
        return prefix + ": " + detail;
    }

    private Integer readInt(JsonNode body, String pointer) {
        JsonNode node = body == null ? null : body.at(pointer);
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asInt();
    }

    private String serializeQuietly(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize gateway payload/result", e);
            return "{\"serializationError\":true}";
        }
    }

    private Integer toInt(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
}
