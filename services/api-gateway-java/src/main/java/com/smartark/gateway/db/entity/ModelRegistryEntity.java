package com.smartark.gateway.db.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "model_registry")
public class ModelRegistryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_name", nullable = false, length = 64, unique = true)
    private String modelName;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "model_role", nullable = false, length = 32)
    private String modelRole;

    @Column(name = "daily_token_limit", nullable = false)
    private Long dailyTokenLimit;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "base_url", length = 255)
    private String baseUrl;

    @JsonIgnore
    @Column(name = "api_key_ciphertext", length = 4096)
    private String apiKeyCiphertext;

    @Column(name = "api_key_masked", length = 64)
    private String apiKeyMasked;

    @Column(name = "crypto_version", length = 16)
    private String cryptoVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getModelRole() { return modelRole; }
    public void setModelRole(String modelRole) { this.modelRole = modelRole; }

    public Long getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(Long dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public void setApiKeyCiphertext(String apiKeyCiphertext) { this.apiKeyCiphertext = apiKeyCiphertext; }

    public String getApiKeyMasked() { return apiKeyMasked; }
    public void setApiKeyMasked(String apiKeyMasked) { this.apiKeyMasked = apiKeyMasked; }

    public String getCryptoVersion() { return cryptoVersion; }
    public void setCryptoVersion(String cryptoVersion) { this.cryptoVersion = cryptoVersion; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
