package com.smartark.gateway.agent.model;

public class QdrantChunkPayload {
    private String chunkUid;
    private float[] vector;
    private String docUid;
    private String source;
    private String title;
    private Integer year;
    private String discipline;
    private String doi;
    private String url;
    private String language;
    private String chunkType;
    private int citationCount;
    private String content;
    private String paperId;

    public String getChunkUid() { return chunkUid; }
    public void setChunkUid(String chunkUid) { this.chunkUid = chunkUid; }

    public float[] getVector() { return vector; }
    public void setVector(float[] vector) { this.vector = vector; }

    public String getDocUid() { return docUid; }
    public void setDocUid(String docUid) { this.docUid = docUid; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

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

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }

    public int getCitationCount() { return citationCount; }
    public void setCitationCount(int citationCount) { this.citationCount = citationCount; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }
}
