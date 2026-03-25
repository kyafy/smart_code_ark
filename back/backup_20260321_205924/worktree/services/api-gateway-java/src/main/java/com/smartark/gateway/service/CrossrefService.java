package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.model.PaperSourceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
public class CrossrefService {
    private static final Logger logger = LoggerFactory.getLogger(CrossrefService.class);
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String baseUrl;
    private final String mailto;
    private final int timeoutMs;

    public CrossrefService(ObjectMapper objectMapper,
                           @Value("${smartark.paper.crossref.base-url:https://api.crossref.org}") String baseUrl,
                           @Value("${smartark.paper.crossref.mailto:aicfu71@gmail.com}") String mailto,
                           @Value("${smartark.paper.crossref.timeout-ms:15000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.mailto = mailto == null ? "" : mailto.trim();
        this.timeoutMs = timeoutMs;
        this.restClient = RestClient.builder().build();
    }

    public List<PaperSourceItem> searchPapers(String query, int limit) {
        int finalLimit = Math.max(1, Math.min(limit, 20));
        String encodedQuery = URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8);
        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/works?query=" + encodedQuery
                + "&rows=" + finalLimit
                + "&mailto=" + URLEncoder.encode(mailto, StandardCharsets.UTF_8)
                + "&select=DOI,title,author,abstract,issued,container-title,is-referenced-by-count,URL,type"
                + "&filter=has-abstract:true"
                + "&sort=relevance&order=desc";

        try {
            String response = restClient.get()
                    .uri(url)
                    .headers(h -> {
                        h.set("User-Agent", "SmartCodeArk/1.0 (mailto:" + mailto + ")");
                        h.set("x-timeout-ms", String.valueOf(timeoutMs));
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            JsonNode items = root.path("message").path("items");
            List<PaperSourceItem> result = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode node : items) {
                    PaperSourceItem item = new PaperSourceItem();
                    String doi = node.path("DOI").asText("");
                    if (doi.isBlank()) continue;
                    item.setPaperId("crossref:" + doi);

                    JsonNode titleNode = node.path("title");
                    if (titleNode.isArray() && !titleNode.isEmpty()) {
                        item.setTitle(titleNode.get(0).asText(""));
                    } else {
                        continue;
                    }
                    if (item.getTitle().isBlank()) continue;

                    List<String> authors = new ArrayList<>();
                    JsonNode authorNode = node.path("author");
                    if (authorNode.isArray()) {
                        for (JsonNode a : authorNode) {
                            String given = a.path("given").asText("");
                            String family = a.path("family").asText("");
                            String name = (given + " " + family).trim();
                            if (!name.isBlank()) {
                                authors.add(name);
                            }
                        }
                    }
                    item.setAuthors(authors);

                    JsonNode issuedParts = node.path("issued").path("date-parts");
                    if (issuedParts.isArray() && !issuedParts.isEmpty()) {
                        JsonNode firstDate = issuedParts.get(0);
                        if (firstDate.isArray() && !firstDate.isEmpty()) {
                            item.setYear(firstDate.get(0).asInt(0));
                            if (item.getYear() == 0) item.setYear(null);
                        }
                    }

                    JsonNode containerTitle = node.path("container-title");
                    if (containerTitle.isArray() && !containerTitle.isEmpty()) {
                        item.setVenue(containerTitle.get(0).asText(""));
                    }

                    item.setUrl(node.path("URL").asText(""));

                    String abstractHtml = node.path("abstract").asText("");
                    String abstractText = stripHtmlTags(abstractHtml);
                    item.setAbstractText(abstractText);
                    item.setEvidenceSnippet(abstractText);

                    int citationCount = node.path("is-referenced-by-count").asInt(0);
                    item.setRelevanceScore(BigDecimal.valueOf(Math.min(citationCount / 100.0, 1.0)));
                    item.setSource("crossref");

                    result.add(item);
                }
            }
            logger.info("crossref_search: query='{}', results={}", query, result.size());
            return result;
        } catch (Exception e) {
            logger.warn("crossref_search_failed: query='{}', error={}", query, e.getMessage());
            return List.of();
        }
    }

    private String stripHtmlTags(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "").trim();
    }
}
