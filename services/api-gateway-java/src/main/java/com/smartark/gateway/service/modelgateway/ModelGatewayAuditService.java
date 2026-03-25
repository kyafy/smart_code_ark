package com.smartark.gateway.service.modelgateway;

import com.smartark.gateway.common.auth.ClientContext;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.db.entity.ModelCallAuditEntity;
import com.smartark.gateway.db.repo.ModelCallAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ModelGatewayAuditService {
    private static final Logger logger = LoggerFactory.getLogger(ModelGatewayAuditService.class);

    private final ModelCallAuditRepository repository;
    private final int requestBodyMaxLength;
    private final int responseBodyMaxLength;

    public ModelGatewayAuditService(
            ModelCallAuditRepository repository,
            @Value("${smartark.model.gateway.audit.request-body-max-length:16000}") int requestBodyMaxLength,
            @Value("${smartark.model.gateway.audit.response-body-max-length:16000}") int responseBodyMaxLength) {
        this.repository = repository;
        this.requestBodyMaxLength = Math.max(1024, requestBodyMaxLength);
        this.responseBodyMaxLength = Math.max(1024, responseBodyMaxLength);
    }

    public void save(ModelGatewayInvocation invocation,
                     ModelGatewayResult result,
                     long latencyMs,
                     boolean success,
                     String errorCode,
                     String errorMessage,
                     String requestBody) {
        try {
            ModelCallAuditEntity entity = new ModelCallAuditEntity();
            entity.setTraceId(defaultString(RequestContext.getTraceId(), "no-trace"));
            entity.setBizScene(invocation.bizScene());
            entity.setProvider(defaultString(invocation.provider(), "dashscope"));
            entity.setEndpoint(invocation.endpoint().value());
            entity.setModelName(defaultString(invocation.modelName(), "unknown"));
            entity.setUpstreamRequestId(result == null ? null : result.upstreamRequestId());
            entity.setUserId(RequestContext.getUserId());
            ClientContext clientContext = RequestContext.getClientContext();
            entity.setClientPlatform(clientContext.platform());
            entity.setAppVersion(clientContext.appVersion());
            entity.setDeviceId(clientContext.deviceId());
            entity.setStream(invocation.stream());
            entity.setSuccess(success);
            entity.setHttpStatus(result == null ? null : result.httpStatus());
            entity.setLatencyMs((int) Math.max(0, latencyMs));
            entity.setTokenInput(result == null ? null : sanitizeToken(result.tokenInput()));
            entity.setTokenOutput(result == null ? null : sanitizeToken(result.tokenOutput()));
            entity.setTokenTotal(result == null ? null : sanitizeToken(result.tokenTotal()));
            entity.setRequestBody(truncate(requestBody, requestBodyMaxLength));
            entity.setResponseBody(truncate(result == null ? null : result.body(), responseBodyMaxLength));
            entity.setErrorCode(truncate(errorCode, 64));
            entity.setErrorMessage(truncate(errorMessage, 512));
            entity.setCreatedAt(LocalDateTime.now());
            repository.save(entity);
        } catch (Exception ex) {
            logger.warn("Failed to persist model call audit, endpoint={}, model={}",
                    invocation.endpoint().value(), invocation.modelName(), ex);
        }
    }

    private Integer sanitizeToken(Integer token) {
        return token == null ? null : Math.max(0, token);
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
