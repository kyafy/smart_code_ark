package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_topic_session")
public class PaperTopicSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "project_id", length = 64)
    private String projectId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "topic", nullable = false, length = 512)
    private String topic;

    @Column(name = "discipline", nullable = false, length = 128)
    private String discipline;

    @Column(name = "degree_level", nullable = false, length = 64)
    private String degreeLevel;

    @Column(name = "method_preference", length = 64)
    private String methodPreference;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "topic_refined", columnDefinition = "text")
    private String topicRefined;

    @Column(name = "research_questions_json", columnDefinition = "json")
    private String researchQuestionsJson;

    @Column(name = "suggested_topics_json", columnDefinition = "text")
    private String suggestedTopicsJson;

    @Column(name = "suggestion_round")
    private Integer suggestionRound = 0;

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

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDiscipline() {
        return discipline;
    }

    public void setDiscipline(String discipline) {
        this.discipline = discipline;
    }

    public String getDegreeLevel() {
        return degreeLevel;
    }

    public void setDegreeLevel(String degreeLevel) {
        this.degreeLevel = degreeLevel;
    }

    public String getMethodPreference() {
        return methodPreference;
    }

    public void setMethodPreference(String methodPreference) {
        this.methodPreference = methodPreference;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTopicRefined() {
        return topicRefined;
    }

    public void setTopicRefined(String topicRefined) {
        this.topicRefined = topicRefined;
    }

    public String getResearchQuestionsJson() {
        return researchQuestionsJson;
    }

    public void setResearchQuestionsJson(String researchQuestionsJson) {
        this.researchQuestionsJson = researchQuestionsJson;
    }

    public String getSuggestedTopicsJson() {
        return suggestedTopicsJson;
    }

    public void setSuggestedTopicsJson(String suggestedTopicsJson) {
        this.suggestedTopicsJson = suggestedTopicsJson;
    }

    public Integer getSuggestionRound() {
        return suggestionRound;
    }

    public void setSuggestionRound(Integer suggestionRound) {
        this.suggestionRound = suggestionRound;
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
