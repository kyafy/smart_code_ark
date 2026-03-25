package com.smartark.gateway.service.modelgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OpenAiCompatibleModelGatewayServiceTest {

    private final OpenAiCompatibleModelGatewayService service = new OpenAiCompatibleModelGatewayService(
            new ObjectMapper(),
            mock(ModelGatewayAuditService.class),
            45_000,
            2,
            true,
            false,
            "http://localhost:18080",
            45_000);

    @Test
    void normalizeBaseUrl_appendsV1ForCompatibleModeBase() {
        String normalized = service.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");

        assertThat(normalized).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    @Test
    void normalizeBaseUrl_stripsEndpointSuffix() {
        String normalized = service.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");

        assertThat(normalized).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    @Test
    void invoke_rejectsEmptyPayloadBeforeNetworkCall() {
        ModelGatewayInvocation invocation = new ModelGatewayInvocation(
                ModelGatewayEndpoint.CHAT_COMPLETIONS,
                "dashscope",
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode",
                "sk-test",
                Map.of(),
                "unit_test",
                null);

        assertThatThrownBy(() -> service.invoke(invocation))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.MODEL_BAD_REQUEST);
    }

    @Test
    void invokeStreaming_rejectsNonChatEndpoint() {
        ModelGatewayInvocation invocation = new ModelGatewayInvocation(
                ModelGatewayEndpoint.EMBEDDINGS,
                "dashscope",
                "text-embedding-v3",
                "https://dashscope.aliyuncs.com/compatible-mode",
                "sk-test",
                Map.of("model", "text-embedding-v3", "input", "hello", "stream", true),
                "unit_test",
                null
        );

        assertThatThrownBy(() -> service.invokeStreaming(invocation, delta -> {}))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.MODEL_UNSUPPORTED_OPERATION);
    }

    @Test
    void invokeStreaming_requiresStreamFlag() {
        ModelGatewayInvocation invocation = new ModelGatewayInvocation(
                ModelGatewayEndpoint.CHAT_COMPLETIONS,
                "dashscope",
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode",
                "sk-test",
                Map.of("model", "qwen-plus", "messages", Map.of(), "stream", false),
                "unit_test",
                null
        );

        assertThatThrownBy(() -> service.invokeStreaming(invocation, delta -> {}))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCodes.MODEL_BAD_REQUEST);
    }
}
