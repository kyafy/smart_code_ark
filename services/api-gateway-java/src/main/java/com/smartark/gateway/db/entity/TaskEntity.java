package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "tasks")
public class TaskEntity {
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "task_type", nullable = false, length = 32)
    private String taskType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "progress", nullable = false)
    private Integer progress;

    @Column(name = "current_step", length = 64)
    private String currentStep;

    @Column(name = "error_code", length = 16)
    private String errorCode;

    @Column(name = "error_message", length = 255)
    private String errorMessage;

    @Column(name = "result_url", length = 512)
    private String resultUrl;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "delivery_level_requested", length = 32)
    private String deliveryLevelRequested;

    @Column(name = "delivery_level_actual", length = 32)
    private String deliveryLevelActual;

    @Column(name = "delivery_status", length = 32)
    private String deliveryStatus;

    @Column(name = "template_id", length = 128)
    private String templateId;

    @Column(name = "codegen_engine", length = 32)
    private String codegenEngine;

    @Column(name = "deploy_mode", length = 32)
    private String deployMode;

    @Column(name = "deploy_env", length = 32)
    private String deployEnv;

    @Column(name = "strict_delivery")
    private Boolean strictDelivery;

    @Column(name = "auto_build_image")
    private Boolean autoBuildImage;

    @Column(name = "auto_push_image")
    private Boolean autoPushImage;

    @Column(name = "auto_deploy_target")
    private Boolean autoDeployTarget;

    @Column(name = "release_status", length = 32)
    private String releaseStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getProgress() {
        return progress;
    }

    public void setProgress(Integer progress) {
        this.progress = progress;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
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
        this.errorMessage = truncate(errorMessage, 255);
    }

    public String getResultUrl() {
        return resultUrl;
    }

    public void setResultUrl(String resultUrl) {
        this.resultUrl = resultUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getDeliveryLevelRequested() {
        return deliveryLevelRequested;
    }

    public void setDeliveryLevelRequested(String deliveryLevelRequested) {
        this.deliveryLevelRequested = truncate(deliveryLevelRequested, 32);
    }

    public String getDeliveryLevelActual() {
        return deliveryLevelActual;
    }

    public void setDeliveryLevelActual(String deliveryLevelActual) {
        this.deliveryLevelActual = truncate(deliveryLevelActual, 32);
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public void setDeliveryStatus(String deliveryStatus) {
        this.deliveryStatus = truncate(deliveryStatus, 32);
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = truncate(templateId, 128);
    }

    public String getCodegenEngine() {
        return codegenEngine;
    }

    public void setCodegenEngine(String codegenEngine) {
        this.codegenEngine = truncate(codegenEngine, 32);
    }

    public String getDeployMode() {
        return deployMode;
    }

    public void setDeployMode(String deployMode) {
        this.deployMode = truncate(deployMode, 32);
    }

    public String getDeployEnv() {
        return deployEnv;
    }

    public void setDeployEnv(String deployEnv) {
        this.deployEnv = truncate(deployEnv, 32);
    }

    public Boolean getStrictDelivery() {
        return strictDelivery;
    }

    public void setStrictDelivery(Boolean strictDelivery) {
        this.strictDelivery = strictDelivery;
    }

    public Boolean getAutoBuildImage() {
        return autoBuildImage;
    }

    public void setAutoBuildImage(Boolean autoBuildImage) {
        this.autoBuildImage = autoBuildImage;
    }

    public Boolean getAutoPushImage() {
        return autoPushImage;
    }

    public void setAutoPushImage(Boolean autoPushImage) {
        this.autoPushImage = autoPushImage;
    }

    public Boolean getAutoDeployTarget() {
        return autoDeployTarget;
    }

    public void setAutoDeployTarget(Boolean autoDeployTarget) {
        this.autoDeployTarget = autoDeployTarget;
    }

    public String getReleaseStatus() {
        return releaseStatus;
    }

    public void setReleaseStatus(String releaseStatus) {
        this.releaseStatus = truncate(releaseStatus, 32);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }
}
