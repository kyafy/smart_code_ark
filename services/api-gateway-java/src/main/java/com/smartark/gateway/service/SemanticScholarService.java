package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.model.PaperSourceItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class SemanticScholarService {
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final int timeoutMs;

    public SemanticScholarService(ObjectMapper objectMapper,
                                  @Value("${smartark.paper.semantic-scholar.base-url:https://api.semanticscholar.org}") String baseUrl,
                                  @Value("${smartark.paper.semantic-scholar.api-key:}") String apiKey,
                                  @Value("${smartark.paper.semantic-scholar.timeout-ms:10000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.timeoutMs = timeoutMs;
        this.restClient = RestClient.builder().build();
    }

    public List<PaperSourceItem> searchPapers(String query, int limit) {
        int finalLimit = Math.max(1, Math.min(limit, 20));
        String encodedQuery = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/graph/v1/paper/search?query=" + encodedQuery
                + "&limit=" + finalLimit
                + "&fields=paperId,title,authors,year,venue,url,abstract";

        try {
            String response = restClient.get()
                    .uri(url)
                    .headers(h -> {
                        if (!apiKey.isBlank()) {
                            h.set("x-api-key", apiKey);
                        }
                        h.set("x-timeout-ms", String.valueOf(timeoutMs));
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");
            List<PaperSourceItem> result = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    PaperSourceItem item = new PaperSourceItem();
                    item.setPaperId(node.path("paperId").asText(""));
                    item.setTitle(node.path("title").asText(""));
                    List<String> authors = new ArrayList<>();
                    JsonNode authorsNode = node.path("authors");
                    if (authorsNode.isArray()) {
                        for (JsonNode a : authorsNode) {
                            String name = a.path("name").asText("");
                            if (!name.isBlank()) {
                                authors.add(name);
                            }
                        }
                    }
                    item.setAuthors(authors);
                    if (!node.path("year").isMissingNode() && !node.path("year").isNull()) {
                        item.setYear(node.path("year").asInt());
                    }
                    item.setVenue(node.path("venue").asText(""));
                    item.setUrl(node.path("url").asText(""));
                    item.setAbstractText(node.path("abstract").asText(""));
                    item.setEvidenceSnippet(node.path("abstract").asText(""));
                    item.setRelevanceScore(BigDecimal.valueOf(1.0));
                    if (!item.getPaperId().isBlank() && !item.getTitle().isBlank()) {
                        result.add(item);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
