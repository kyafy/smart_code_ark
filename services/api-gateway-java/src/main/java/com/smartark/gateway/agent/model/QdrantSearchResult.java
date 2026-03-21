package com.smartark.gateway.agent.model;

import java.util.Map;

public class QdrantSearchResult {
    private String chunkUid;
    private Map<String, Object> payload;
    private float score;

    public QdrantSearchResult() {}

    public QdrantSearchResult(String chunkUid, Map<String, Object> payload, float score) {
        this.chunkUid = chunkUid;
        this.payload = payload;
        this.score = score;
    }

    public String getChunkUid() { return chunkUid; }
    public void setChunkUid(String chunkUid) { this.chunkUid = chunkUid; }

    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }

    public float getScore() { return score; }
    public void setScore(float score) { this.score = score; }
}
