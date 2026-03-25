package com.smartark.gateway.agent.model;

public class RagEvidenceItem {
    private String chunkUid;
    private String docUid;
    private String paperId;
    private String title;
    private String content;
    private String url;
    private Integer year;
    private double vectorScore;
    private double rerankScore;
    private String chunkType;

    public String getChunkUid() { return chunkUid; }
    public void setChunkUid(String chunkUid) { this.chunkUid = chunkUid; }

    public String getDocUid() { return docUid; }
    public void setDocUid(String docUid) { this.docUid = docUid; }

    public String getPaperId() { return paperId; }
    public void setPaperId(String paperId) { this.paperId = paperId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }

    public double getVectorScore() { return vectorScore; }
    public void setVectorScore(double vectorScore) { this.vectorScore = vectorScore; }

    public double getRerankScore() { return rerankScore; }
    public void setRerankScore(double rerankScore) { this.rerankScore = rerankScore; }

    public String getChunkType() { return chunkType; }
    public void setChunkType(String chunkType) { this.chunkType = chunkType; }
}
