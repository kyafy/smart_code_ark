package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
public class ProjectEntity {
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "project_type", nullable = false, length = 32)
    private String projectType;

    @Column(name = "stack_backend", length = 32)
    private String stackBackend;

    @Column(name = "stack_frontend", length = 32)
    private String stackFrontend;

    @Column(name = "stack_db", length = 32)
    private String stackDb;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProjectType() {
        return projectType;
    }

    public void setProjectType(String projectType) {
        this.projectType = projectType;
    }

    public String getStackBackend() {
        return stackBackend;
    }

    public void setStackBackend(String stackBackend) {
        this.stackBackend = stackBackend;
    }

    public String getStackFrontend() {
        return stackFrontend;
    }

    public void setStackFrontend(String stackFrontend) {
        this.stackFrontend = stackFrontend;
    }

    public String getStackDb() {
        return stackDb;
    }

    public void setStackDb(String stackDb) {
        this.stackDb = stackDb;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
