package com.smartark.gateway.service.modelgateway;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatCompletionsCommandTest {

    @Test
    void fromLegacyPayload_extractsTypedFields() {
        Map<String, Object> payload = Map.of(
                "model", "qwen-plus",
                "messages", List.of(Map.of("role", "user", "content", "hello")),
                "stream", true,
                "temperature", 0.2,
                "top_p", 0.9,
                "max_tokens", 512,
                "response_format", Map.of("type", "json_object"),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "query"))),
                "tool_choice", "auto",
                "presence_penalty", 0.1
        );

        ChatCompletionsCommand command = ChatCompletionsCommand.fromLegacyPayload(payload);

        assertThat(command.model()).isEqualTo("qwen-plus");
        assertThat(command.stream()).isTrue();
        assertThat(command.temperature()).isEqualTo(0.2);
        assertThat(command.topP()).isEqualTo(0.9);
        assertThat(command.maxTokens()).isEqualTo(512);
        assertThat(command.responseFormat()).isEqualTo(Map.of("type", "json_object"));
        assertThat(command.tools()).isInstanceOf(List.class);
        assertThat(command.toolChoice()).isEqualTo("auto");
        assertThat(command.additionalOptions()).containsEntry("presence_penalty", 0.1);
    }

    @Test
    void toPayload_containsCoreAndTypedExtensionFields() {
        ChatCompletionsCommand command = new ChatCompletionsCommand(
                "qwen-plus",
                List.of(Map.of("role", "user", "content", "hello")),
                false,
                0.1,
                0.8,
                256,
                Map.of("type", "json_object"),
                List.of(Map.of("type", "function")),
                "auto",
                Map.of("frequency_penalty", 0.2)
        );

        Map<String, Object> payload = command.toPayload();

        assertThat(payload).containsEntry("model", "qwen-plus");
        assertThat(payload).containsEntry("stream", false);
        assertThat(payload).containsEntry("max_tokens", 256);
        assertThat(payload).containsEntry("tool_choice", "auto");
        assertThat(payload).containsKey("response_format");
        assertThat(payload).containsKey("tools");
        assertThat(payload).containsEntry("frequency_penalty", 0.2);
    }

    @Test
    void fromLegacyPayload_rejectsMissingModel() {
        Map<String, Object> payload = Map.of(
                "messages", List.of(Map.of("role", "user", "content", "hello"))
        );

        assertThatThrownBy(() -> ChatCompletionsCommand.fromLegacyPayload(payload))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.MODEL_BAD_REQUEST);
    }
}
