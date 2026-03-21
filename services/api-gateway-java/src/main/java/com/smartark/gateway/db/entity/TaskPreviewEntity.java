package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "task_preview")
public class TaskPreviewEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "phase", length = 32)
    private String phase;

    @Column(name = "preview_url", length = 512)
    private String previewUrl;

    @Column(name = "runtime_id", length = 128)
    private String runtimeId;

    @Column(name = "build_log_url", length = 512)
    private String buildLogUrl;

    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "last_error_code")
    private Integer lastErrorCode;

    @Column(name = "last_health_check_at")
    private LocalDateTime lastHealthCheckAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    public void setPreviewUrl(String previewUrl) {
        this.previewUrl = previewUrl;
    }

    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public String getBuildLogUrl() {
        return buildLogUrl;
    }

    public void setBuildLogUrl(String buildLogUrl) {
        this.buildLogUrl = buildLogUrl;
    }

    public LocalDateTime getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(LocalDateTime expireAt) {
        this.expireAt = expireAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Integer getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(Integer lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public LocalDateTime getLastHealthCheckAt() {
        return lastHealthCheckAt;
    }

    public void setLastHealthCheckAt(LocalDateTime lastHealthCheckAt) {
        this.lastHealthCheckAt = lastHealthCheckAt;
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
}
