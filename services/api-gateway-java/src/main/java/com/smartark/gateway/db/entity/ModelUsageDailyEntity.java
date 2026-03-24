package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "model_usage_daily")
public class ModelUsageDailyEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_name", nullable = false, length = 64)
    private String modelName;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "call_count", nullable = false)
    private Long callCount;

    @Column(name = "token_input", nullable = false)
    private Long tokenInput;

    @Column(name = "token_output", nullable = false)
    private Long tokenOutput;

    @Column(name = "token_total", nullable = false)
    private Long tokenTotal;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public LocalDate getUsageDate() { return usageDate; }
    public void setUsageDate(LocalDate usageDate) { this.usageDate = usageDate; }

    public Long getCallCount() { return callCount; }
    public void setCallCount(Long callCount) { this.callCount = callCount; }

    public Long getTokenInput() { return tokenInput; }
    public void setTokenInput(Long tokenInput) { this.tokenInput = tokenInput; }

    public Long getTokenOutput() { return tokenOutput; }
    public void setTokenOutput(Long tokenOutput) { this.tokenOutput = tokenOutput; }

    public Long getTokenTotal() { return tokenTotal; }
    public void setTokenTotal(Long tokenTotal) { this.tokenTotal = tokenTotal; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
