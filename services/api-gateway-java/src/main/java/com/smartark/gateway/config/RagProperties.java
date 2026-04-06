package com.smartark.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Configuration
@ConfigurationProperties(prefix = "smartark.rag")
@Validated
public class RagProperties {

    private boolean enabled = true;
    private String embeddingBaseUrl = "";

    @NotBlank(message = "embedding-model 不能为空")
    @Pattern(regexp = "^[a-z0-9.-]+$", message = "embedding-model 必须为全小写字母、数字、点或连字符")
    private String embeddingModel = "text-embedding-v3";

    private int embeddingDimension = 1024;
    private int chunkMaxTokens = 512;
    private int chunkOverlapTokens = 64;
    private int retrieveTopK = 30;
    private int rerankTopN = 15;
    private Qdrant qdrant = new Qdrant();

    public static class Qdrant {
        private String host = "localhost";
        private int port = 6334;
        private String collectionName = "paper_chunks";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }
    }

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
        this.qdrant = qdrant;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEmbeddingBaseUrl() {
        return embeddingBaseUrl;
    }

    public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
        this.embeddingBaseUrl = embeddingBaseUrl;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public void setEmbeddingModel(String embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public void setEmbeddingDimension(int embeddingDimension) {
        this.embeddingDimension = embeddingDimension;
    }

    public int getChunkMaxTokens() {
        return chunkMaxTokens;
    }

    public void setChunkMaxTokens(int chunkMaxTokens) {
        this.chunkMaxTokens = chunkMaxTokens;
    }

    public int getChunkOverlapTokens() {
        return chunkOverlapTokens;
    }

    public void setChunkOverlapTokens(int chunkOverlapTokens) {
        this.chunkOverlapTokens = chunkOverlapTokens;
    }

    public int getRetrieveTopK() {
        return retrieveTopK;
    }

    public void setRetrieveTopK(int retrieveTopK) {
        this.retrieveTopK = retrieveTopK;
    }

    public int getRerankTopN() {
        return rerankTopN;
    }

    public void setRerankTopN(int rerankTopN) {
        this.rerankTopN = rerankTopN;
    }
}
