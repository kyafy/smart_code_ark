package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "model_call_audit")
public class ModelCallAuditEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    @Column(name = "biz_scene", length = 64)
    private String bizScene;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "endpoint", nullable = false, length = 64)
    private String endpoint;

    @Column(name = "model_name", nullable = false, length = 128)
    private String modelName;

    @Column(name = "upstream_request_id", length = 128)
    private String upstreamRequestId;

    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "client_platform", length = 32)
    private String clientPlatform;

    @Column(name = "app_version", length = 64)
    private String appVersion;

    @Column(name = "device_id", length = 128)
    private String deviceId;

    @Column(name = "stream", nullable = false)
    private Boolean stream;

    @Column(name = "success", nullable = false)
    private Boolean success;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "latency_ms", nullable = false)
    private Integer latencyMs;

    @Column(name = "token_input")
    private Integer tokenInput;

    @Column(name = "token_output")
    private Integer tokenOutput;

    @Column(name = "token_total")
    private Integer tokenTotal;

    @Column(name = "request_body")
    private String requestBody;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getBizScene() {
        return bizScene;
    }

    public void setBizScene(String bizScene) {
        this.bizScene = bizScene;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getUpstreamRequestId() {
        return upstreamRequestId;
    }

    public void setUpstreamRequestId(String upstreamRequestId) {
        this.upstreamRequestId = upstreamRequestId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getClientPlatform() {
        return clientPlatform;
    }

    public void setClientPlatform(String clientPlatform) {
        this.clientPlatform = clientPlatform;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Integer getTokenInput() {
        return tokenInput;
    }

    public void setTokenInput(Integer tokenInput) {
        this.tokenInput = tokenInput;
    }

    public Integer getTokenOutput() {
        return tokenOutput;
    }

    public void setTokenOutput(Integer tokenOutput) {
        this.tokenOutput = tokenOutput;
    }

    public Integer getTokenTotal() {
        return tokenTotal;
    }

    public void setTokenTotal(Integer tokenTotal) {
        this.tokenTotal = tokenTotal;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
