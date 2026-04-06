package com.smartark.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "smartark.model")
@Validated
public class ModelProperties {

    private String baseUrl = "";
    private String apiKey = "";
    private boolean mockEnabled = false;

    @NotBlank(message = "chat-model 不能为空")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "chat-model 必须为全小写字母、数字、点或连字符")
    private String chatModel = "qwen-plus";

    @NotBlank(message = "code-model 不能为空")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "code-model 必须为全小写字母、数字、点或连字符")
    private String codeModel = "qwen-plus";

    @NotBlank(message = "paper-model 不能为空")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "paper-model 必须为全小写字母、数字、点或连字符")
    private String paperModel = "qwen-plus";

    private boolean schemaValidationEnabled = true;
    private int correctiveRetryMax = 2;
    private String cryptoMasterKey = "change-me-please";
    private Gateway gateway = new Gateway();

    public String getCryptoMasterKey() {
        return cryptoMasterKey;
    }

    public void setCryptoMasterKey(String cryptoMasterKey) {
        this.cryptoMasterKey = cryptoMasterKey;
    }

    public static class Gateway {
        private int defaultTimeoutMs = 45000;
        private int maxRetries = 2;
        private boolean responseValidationEnabled = true;
        private Audit audit = new Audit();

        public static class Audit {
            private int requestBodyMaxLength = 16000;
            private int responseBodyMaxLength = 16000;

            public int getRequestBodyMaxLength() {
                return requestBodyMaxLength;
            }

            public void setRequestBodyMaxLength(int requestBodyMaxLength) {
                this.requestBodyMaxLength = requestBodyMaxLength;
            }

            public int getResponseBodyMaxLength() {
                return responseBodyMaxLength;
            }

            public void setResponseBodyMaxLength(int responseBodyMaxLength) {
                this.responseBodyMaxLength = responseBodyMaxLength;
            }
        }

        public Audit getAudit() {
            return audit;
        }

        public void setAudit(Audit audit) {
            this.audit = audit;
        }

        public int getDefaultTimeoutMs() {
            return defaultTimeoutMs;
        }

        public void setDefaultTimeoutMs(int defaultTimeoutMs) {
            this.defaultTimeoutMs = defaultTimeoutMs;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public boolean isResponseValidationEnabled() {
            return responseValidationEnabled;
        }

        public void setResponseValidationEnabled(boolean responseValidationEnabled) {
            this.responseValidationEnabled = responseValidationEnabled;
        }
    }

    public Gateway getGateway() {
        return gateway;
    }

    public void setGateway(Gateway gateway) {
        this.gateway = gateway;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    public String getChatModel() {
        return chatModel;
    }

    public void setChatModel(String chatModel) {
        this.chatModel = chatModel;
    }

    public String getCodeModel() {
        return codeModel;
    }

    public void setCodeModel(String codeModel) {
        this.codeModel = codeModel;
    }

    public String getPaperModel() {
        return paperModel;
    }

    public void setPaperModel(String paperModel) {
        this.paperModel = paperModel;
    }

    public boolean isSchemaValidationEnabled() {
        return schemaValidationEnabled;
    }

    public void setSchemaValidationEnabled(boolean schemaValidationEnabled) {
        this.schemaValidationEnabled = schemaValidationEnabled;
    }

    public int getCorrectiveRetryMax() {
        return correctiveRetryMax;
    }

    public void setCorrectiveRetryMax(int correctiveRetryMax) {
        this.correctiveRetryMax = correctiveRetryMax;
    }
}
