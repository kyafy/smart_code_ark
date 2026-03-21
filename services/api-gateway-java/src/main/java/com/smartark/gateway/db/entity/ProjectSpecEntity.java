package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_specs")
public class ProjectSpecEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "requirement_json", columnDefinition = "json")
    private String requirementJson;

    @Column(name = "domain_json", columnDefinition = "json")
    private String domainJson;

    @Column(name = "api_contract_json", columnDefinition = "json")
    private String apiContractJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getRequirementJson() {
        return requirementJson;
    }

    public void setRequirementJson(String requirementJson) {
        this.requirementJson = requirementJson;
    }

    public String getDomainJson() {
        return domainJson;
    }

    public void setDomainJson(String domainJson) {
        this.domainJson = domainJson;
    }

    public String getApiContractJson() {
        return apiContractJson;
    }

    public void setApiContractJson(String apiContractJson) {
        this.apiContractJson = apiContractJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
