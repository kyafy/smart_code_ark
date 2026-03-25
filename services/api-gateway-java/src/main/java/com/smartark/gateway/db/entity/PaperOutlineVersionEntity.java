package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_outline_versions")
public class PaperOutlineVersionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "citation_style", nullable = false, length = 32)
    private String citationStyle;

    @Column(name = "outline_json", nullable = false, columnDefinition = "json")
    private String outlineJson;

    @Column(name = "manuscript_json", columnDefinition = "json")
    private String manuscriptJson;

    @Column(name = "quality_report_json", columnDefinition = "json")
    private String qualityReportJson;

    @Column(name = "quality_score", precision = 5, scale = 2)
    private java.math.BigDecimal qualityScore;

    @Column(name = "rewrite_round", nullable = false)
    private Integer rewriteRound = 0;

    @Column(name = "chapter_evidence_map_json", columnDefinition = "text")
    private String chapterEvidenceMapJson;

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getCitationStyle() {
        return citationStyle;
    }

    public void setCitationStyle(String citationStyle) {
        this.citationStyle = citationStyle;
    }

    public String getOutlineJson() {
        return outlineJson;
    }

    public void setOutlineJson(String outlineJson) {
        this.outlineJson = outlineJson;
    }

    public String getQualityReportJson() {
        return qualityReportJson;
    }

    public void setQualityReportJson(String qualityReportJson) {
        this.qualityReportJson = qualityReportJson;
    }

    public String getManuscriptJson() {
        return manuscriptJson;
    }

    public void setManuscriptJson(String manuscriptJson) {
        this.manuscriptJson = manuscriptJson;
    }

    public java.math.BigDecimal getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(java.math.BigDecimal qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Integer getRewriteRound() {
        return rewriteRound;
    }

    public void setRewriteRound(Integer rewriteRound) {
        this.rewriteRound = rewriteRound;
    }

    public String getChapterEvidenceMapJson() {
        return chapterEvidenceMapJson;
    }

    public void setChapterEvidenceMapJson(String chapterEvidenceMapJson) {
        this.chapterEvidenceMapJson = chapterEvidenceMapJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    public void prePersist() {
        if (rewriteRound == null) {
            rewriteRound = 0;
        }
    }
}
