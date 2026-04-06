package com.smartark.gateway.service;

import com.smartark.gateway.agent.model.QdrantChunkPayload;
import com.smartark.gateway.agent.model.QdrantSearchResult;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.smartark.gateway.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.ConditionFactory.matchKeyword;

@Service
@ConditionalOnProperty(name = "smartark.rag.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantService {
    private static final Logger logger = LoggerFactory.getLogger(QdrantService.class);

    private final QdrantClient qdrantClient;
    private final String collectionName;
    private final int embeddingDimension;

    public QdrantService(
            QdrantClient qdrantClient,
            RagProperties ragProperties
    ) {
        this.qdrantClient = qdrantClient;
        this.collectionName = ragProperties.getQdrant().getCollectionName();
        this.embeddingDimension = ragProperties.getEmbeddingDimension();
    }

    @PostConstruct
    public void init() {
        try {
            boolean exists = qdrantClient.collectionExistsAsync(collectionName).get();
            if (!exists) {
                logger.info("Creating Qdrant collection: {}", collectionName);
                qdrantClient.createCollectionAsync(collectionName,
                        Collections.VectorParams.newBuilder()
                                .setDistance(Collections.Distance.Cosine)
                                .setSize(embeddingDimension)
                                .setHnswConfig(Collections.HnswConfigDiff.newBuilder()
                                        .setM(16)
                                        .setEfConstruct(200)
                                        .build())
                                .setOnDisk(true)
                                .build()
                ).get();

                // Create payload indexes
                qdrantClient.createPayloadIndexAsync(collectionName, "discipline",
                        Collections.PayloadSchemaType.Keyword, null, null, null, null).get();
                qdrantClient.createPayloadIndexAsync(collectionName, "year",
                        Collections.PayloadSchemaType.Integer, null, null, null, null).get();
                qdrantClient.createPayloadIndexAsync(collectionName, "source",
                        Collections.PayloadSchemaType.Keyword, null, null, null, null).get();

                logger.info("Qdrant collection '{}' created with payload indexes", collectionName);
            } else {
                logger.info("Qdrant collection '{}' already exists", collectionName);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize Qdrant collection", e);
        }
    }

    public void upsertChunks(List<QdrantChunkPayload> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        try {
            List<Points.PointStruct> points = new ArrayList<>();
            for (QdrantChunkPayload chunk : chunks) {
                Map<String, JsonWithInt.Value> payloadMap = new HashMap<>();
                payloadMap.put("chunkUid", value(chunk.getChunkUid()));
                payloadMap.put("docUid", value(chunk.getDocUid()));
                payloadMap.put("source", value(chunk.getSource()));
                payloadMap.put("title", value(chunk.getTitle()));
                payloadMap.put("content", value(chunk.getContent()));
                payloadMap.put("chunkType", value(chunk.getChunkType()));
                payloadMap.put("citationCount", value((long) chunk.getCitationCount()));
                payloadMap.put("paperId", value(chunk.getPaperId()));
                if (chunk.getYear() != null) {
                    payloadMap.put("year", value((long) chunk.getYear()));
                }
                if (chunk.getDiscipline() != null) {
                    payloadMap.put("discipline", value(chunk.getDiscipline()));
                }
                if (chunk.getDoi() != null) {
                    payloadMap.put("doi", value(chunk.getDoi()));
                }
                if (chunk.getUrl() != null) {
                    payloadMap.put("url", value(chunk.getUrl()));
                }
                if (chunk.getLanguage() != null) {
                    payloadMap.put("language", value(chunk.getLanguage()));
                }

                Points.PointStruct point = Points.PointStruct.newBuilder()
                        .setId(id(UUID.nameUUIDFromBytes(chunk.getChunkUid().getBytes())))
                        .setVectors(vectors(chunk.getVector()))
                        .putAllPayload(payloadMap)
                        .build();
                points.add(point);
            }

            qdrantClient.upsertAsync(collectionName, points).get();
            logger.debug("Upserted {} chunks to Qdrant", chunks.size());
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Failed to upsert chunks to Qdrant", e);
            throw new BusinessException(ErrorCodes.RAG_QDRANT_UNAVAILABLE, "Qdrant upsert失败: " + e.getMessage());
        }
    }

    public List<QdrantSearchResult> search(float[] queryVector, int topK, String disciplineFilter) {
        try {
            Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(toFloatList(queryVector))
                    .setLimit(topK)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .setParams(Points.SearchParams.newBuilder()
                            .setHnswEf(64)
                            .build());

            if (disciplineFilter != null && !disciplineFilter.isBlank()) {
                searchBuilder.setFilter(Points.Filter.newBuilder()
                        .addMust(matchKeyword("discipline", disciplineFilter))
                        .build());
            }

            List<Points.ScoredPoint> scoredPoints = qdrantClient.searchAsync(searchBuilder.build()).get();
            List<QdrantSearchResult> results = new ArrayList<>();
            for (Points.ScoredPoint sp : scoredPoints) {
                Map<String, Object> payload = new HashMap<>();
                sp.getPayloadMap().forEach((key, val) -> payload.put(key, extractValue(val)));
                String chunkUid = payload.getOrDefault("chunkUid", "").toString();
                results.add(new QdrantSearchResult(chunkUid, payload, sp.getScore()));
            }
            return results;
        } catch (ExecutionException | InterruptedException e) {
            logger.error("Qdrant search failed", e);
            throw new BusinessException(ErrorCodes.RAG_RETRIEVE_FAILED, "Qdrant检索失败: " + e.getMessage());
        }
    }

    public void deleteByDocUids(List<String> docUids) {
        if (docUids == null || docUids.isEmpty()) {
            return;
        }
        try {
            for (String docUid : docUids) {
                Points.Filter f = Points.Filter.newBuilder()
                        .addMust(matchKeyword("docUid", docUid))
                        .build();
                qdrantClient.deleteAsync(collectionName, f).get();
            }
            logger.debug("Deleted points for {} doc UIDs from Qdrant", docUids.size());
        } catch (ExecutionException | InterruptedException e) {
            logger.warn("Failed to delete from Qdrant", e);
        }
    }

    public long getPointCount() {
        try {
            Collections.CollectionInfo info = qdrantClient.getCollectionInfoAsync(collectionName).get();
            return info.getPointsCount();
        } catch (Exception e) {
            logger.warn("Failed to get Qdrant collection stats", e);
            return 0;
        }
    }

    public String getCollectionName() {
        return collectionName;
    }

    public String getCollectionStatus() {
        try {
            qdrantClient.getCollectionInfoAsync(collectionName).get();
            return "green";
        } catch (Exception e) {
            return "red";
        }
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) {
            list.add(f);
        }
        return list;
    }

    private Object extractValue(JsonWithInt.Value val) {
        if (val.hasStringValue()) return val.getStringValue();
        if (val.hasIntegerValue()) return val.getIntegerValue();
        if (val.hasDoubleValue()) return val.getDoubleValue();
        if (val.hasBoolValue()) return val.getBoolValue();
        return val.toString();
    }
}
