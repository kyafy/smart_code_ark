package com.smartark.gateway.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "smartark.rag.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantConfig {
    private static final Logger logger = LoggerFactory.getLogger(QdrantConfig.class);

    @Value("${smartark.rag.qdrant.host:localhost}")
    private String host;

    @Value("${smartark.rag.qdrant.port:6334}")
    private int port;

    private QdrantClient qdrantClient;

    @Bean
    public QdrantClient qdrantClient() {
        logger.info("Connecting to Qdrant at {}:{}", host, port);
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, port, false).build();
        this.qdrantClient = new QdrantClient(grpcClient);
        return this.qdrantClient;
    }

    @PreDestroy
    public void close() {
        if (qdrantClient != null) {
            try {
                qdrantClient.close();
                logger.info("Qdrant client closed");
            } catch (Exception e) {
                logger.warn("Error closing Qdrant client", e);
            }
        }
    }
}
