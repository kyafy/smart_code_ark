package com.smartark.gateway.service.modelgateway;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strongly-typed internal command for chat.completions requests.
 * Keeps the business callsites decoupled from provider-specific wire details.
 */
public record ChatCompletionsCommand(
        String model,
        Object messages,
        Boolean stream,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Object responseFormat,
        Object tools,
        Object toolChoice,
        Map<String, Object> additionalOptions
) {
    public static ChatCompletionsCommand fromLegacyPayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: payload 不能为空");
        }
        String model = asString(payload.get("model"));
        if (model == null || model.isBlank()) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: model 不能为空");
        }
        Object messages = payload.get("messages");
        if (messages == null) {
            throw new BusinessException(ErrorCodes.MODEL_BAD_REQUEST, "模型网关请求非法: messages 不能为空");
        }
        Boolean stream = asBoolean(payload.get("stream"));
        Double temperature = asDouble(payload.get("temperature"));
        Double topP = asDouble(payload.get("top_p"));
        Integer maxTokens = asInt(payload.get("max_tokens"));
        Object responseFormat = payload.get("response_format");
        Object tools = payload.get("tools");
        Object toolChoice = payload.get("tool_choice");

        Map<String, Object> additional = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            String key = entry.getKey();
            if ("model".equals(key) ||
                    "messages".equals(key) ||
                    "stream".equals(key) ||
                    "temperature".equals(key) ||
                    "top_p".equals(key) ||
                    "max_tokens".equals(key) ||
                    "response_format".equals(key) ||
                    "tools".equals(key) ||
                    "tool_choice".equals(key)) {
                continue;
            }
            additional.put(key, entry.getValue());
        }

        return new ChatCompletionsCommand(
                model.trim(),
                messages,
                stream,
                temperature,
                topP,
                maxTokens,
                responseFormat,
                tools,
                toolChoice,
                additional
        );
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        if (stream != null) {
            payload.put("stream", stream);
        }
        if (temperature != null) {
            payload.put("temperature", temperature);
        }
        if (topP != null) {
            payload.put("top_p", topP);
        }
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        if (responseFormat != null) {
            payload.put("response_format", responseFormat);
        }
        if (tools != null) {
            payload.put("tools", tools);
        }
        if (toolChoice != null) {
            payload.put("tool_choice", toolChoice);
        }
        if (additionalOptions != null && !additionalOptions.isEmpty()) {
            payload.putAll(additionalOptions);
        }
        return payload;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }
}
