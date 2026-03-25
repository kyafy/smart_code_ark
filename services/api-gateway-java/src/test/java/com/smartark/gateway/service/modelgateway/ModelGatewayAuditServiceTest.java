package com.smartark.gateway.service.modelgateway;

import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.db.entity.ModelCallAuditEntity;
import com.smartark.gateway.db.repo.ModelCallAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ModelGatewayAuditServiceTest {

    @Mock
    private ModelCallAuditRepository repository;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void save_persistsTraceClientAndTokenMetadata() {
        RequestContext.setTraceId("trace-123");
        RequestContext.setUserId("42");
        RequestContext.setClientPlatform("web");
        RequestContext.setAppVersion("1.0.0");
        RequestContext.setDeviceId("device-1");

        ModelGatewayAuditService service = new ModelGatewayAuditService(repository, 32, 32);
        ModelGatewayInvocation invocation = new ModelGatewayInvocation(
                ModelGatewayEndpoint.CHAT_COMPLETIONS,
                "dashscope",
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode",
                "sk-test",
                Map.of("model", "qwen-plus"),
                "codegen",
                null);
        ModelGatewayResult result = new ModelGatewayResult(
                "{\"ok\":true}",
                "req-1",
                200,
                11,
                7,
                18);

        service.save(invocation, result, 123L, true, null, null, "{\"model\":\"qwen-plus\"}");

        ArgumentCaptor<ModelCallAuditEntity> captor = ArgumentCaptor.forClass(ModelCallAuditEntity.class);
        verify(repository).save(captor.capture());
        ModelCallAuditEntity entity = captor.getValue();
        assertThat(entity.getTraceId()).isEqualTo("trace-123");
        assertThat(entity.getUserId()).isEqualTo("42");
        assertThat(entity.getClientPlatform()).isEqualTo("web");
        assertThat(entity.getAppVersion()).isEqualTo("1.0.0");
        assertThat(entity.getDeviceId()).isEqualTo("device-1");
        assertThat(entity.getEndpoint()).isEqualTo("chat.completions");
        assertThat(entity.getModelName()).isEqualTo("qwen-plus");
        assertThat(entity.getUpstreamRequestId()).isEqualTo("req-1");
        assertThat(entity.getTokenInput()).isEqualTo(11);
        assertThat(entity.getTokenOutput()).isEqualTo(7);
        assertThat(entity.getTokenTotal()).isEqualTo(18);
        assertThat(entity.getSuccess()).isTrue();
    }
}
