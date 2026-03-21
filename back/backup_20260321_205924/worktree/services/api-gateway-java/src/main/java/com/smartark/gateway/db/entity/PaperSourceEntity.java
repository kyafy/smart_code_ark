package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "paper_sources")
public class PaperSourceEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "section_key", length = 128)
    private String sectionKey;

    @Column(name = "paper_id", nullable = false, length = 128)
    private String paperId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "authors_json", columnDefinition = "json")
    private String authorsJson;

    @Column(name = "year")
    private Integer year;

    @Column(name = "venue", length = 255)
    private String venue;

    @Column(name = "url", length = 1024)
    private String url;

    @Column(name = "abstract_text", columnDefinition = "text")
    private String abstractText;

    @Column(name = "evidence_snippet", columnDefinition = "text")
    private String evidenceSnippet;

    @Column(name = "relevance_score", precision = 5, scale = 4)
    private BigDecimal relevanceScore;

<<<<<<< HEAD
=======
    @Column(name = "source", nullable = false, length = 32)
    private String source = "semantic_scholar";

>>>>>>> origin/master
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getSectionKey() {
        return sectionKey;
    }

    public void setSectionKey(String sectionKey) {
        this.sectionKey = sectionKey;
    }

    public String getPaperId() {
        return paperId;
    }

    public void setPaperId(String paperId) {
        this.paperId = paperId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthorsJson() {
        return authorsJson;
    }

    public void setAuthorsJson(String authorsJson) {
        this.authorsJson = authorsJson;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getVenue() {
        return venue;
    }

    public void setVenue(String venue) {
        this.venue = venue;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAbstractText() {
        return abstractText;
    }

    public void setAbstractText(String abstractText) {
        this.abstractText = abstractText;
    }

    public String getEvidenceSnippet() {
        return evidenceSnippet;
    }

    public void setEvidenceSnippet(String evidenceSnippet) {
        this.evidenceSnippet = evidenceSnippet;
    }

    public BigDecimal getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(BigDecimal relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

<<<<<<< HEAD
=======
    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

>>>>>>> origin/master
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
