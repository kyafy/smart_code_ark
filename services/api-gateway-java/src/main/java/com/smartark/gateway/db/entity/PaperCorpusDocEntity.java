package com.smartark.gateway.db.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "paper_corpus_docs")
public class PaperCorpusDocEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "doc_uid", nullable = false, length = 64)
    private String docUid;

    @Column(name = "paper_id", nullable = false, length = 128)
    private String paperId;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "year")
    private Integer year;

    @Column(name = "discipline", length = 128)
    private String discipline;

    @Column(name = "doi", length = 256)
    private String doi;

    @Column(name = "url", length = 1024)
    private String url;

    @Column(name = "language", length = 16)
    private String language;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "citation_count")
    private Integer citationCount;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }

    public String getDocUid() { return docUid; }
    public void setDocUid(String docUid) { this.docUid = docUid; }

    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public String getDiscipline() { return discipline; }
    public void setDiscipline(String discipline) { this.discipline = discipline; }

    public String getDoi() { return doi; }
    public void setDoi(String doi) { this.doi = doi; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Integer getCitationCount() { return citationCount; }
    public void setCitationCount(Integer citationCount) { this.citationCount = citationCount; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
