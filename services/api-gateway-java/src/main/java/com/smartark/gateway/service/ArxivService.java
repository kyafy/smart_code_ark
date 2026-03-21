package com.smartark.gateway.service;

import com.smartark.gateway.agent.model.PaperSourceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ArxivService {
    private static final Logger logger = LoggerFactory.getLogger(ArxivService.class);
    private static final Pattern ARXIV_ID_PATTERN = Pattern.compile("abs/(.+)$");
    private static final Set<String> STEM_KEYWORDS = Set.of(
            "计算机", "软件", "人工智能", "机器学习", "深度学习", "自然语言处理",
            "cs", "ai", "computer", "software", "machine learning", "deep learning",
            "nlp", "artificial intelligence", "data science", "information",
            "物理", "天文", "量子", "physics", "astronomy", "quantum",
            "数学", "统计", "math", "mathematics", "statistics",
            "电气", "电子", "通信", "信号", "electrical", "electronic", "communication", "signal",
            "经济", "金融", "量化", "economics", "finance", "quantitative"
    );

    private final RestClient restClient;
    private final String baseUrl;
    private final int timeoutMs;
    private final int requestIntervalMs;

    public ArxivService(@Value("${smartark.paper.arxiv.base-url:http://export.arxiv.org}") String baseUrl,
                        @Value("${smartark.paper.arxiv.timeout-ms:10000}") int timeoutMs,
                        @Value("${smartark.paper.arxiv.request-interval-ms:3000}") int requestIntervalMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.timeoutMs = timeoutMs;
        this.requestIntervalMs = requestIntervalMs;
        this.restClient = RestClient.builder().build();
    }

    public static boolean supportsDiscipline(String discipline) {
        if (discipline == null || discipline.isBlank()) return false;
        String lower = discipline.toLowerCase();
        return STEM_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public List<PaperSourceItem> searchPapers(String query, int limit) {
        int finalLimit = Math.max(1, Math.min(limit, 30));
        String encodedQuery = URLEncoder.encode("all:" + (query == null ? "" : query), StandardCharsets.UTF_8);
        String url = (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                + "/api/query?search_query=" + encodedQuery
                + "&start=0&max_results=" + finalLimit
                + "&sortBy=relevance&sortOrder=descending";

        try {
            if (requestIntervalMs > 0) {
                Thread.sleep(requestIntervalMs);
            }

            String response = restClient.get()
                    .uri(url)
                    .headers(h -> h.set("x-timeout-ms", String.valueOf(timeoutMs)))
                    .retrieve()
                    .body(String.class);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));

            NodeList entries = doc.getElementsByTagName("entry");
            List<PaperSourceItem> result = new ArrayList<>();

            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                PaperSourceItem item = new PaperSourceItem();

                String id = getElementText(entry, "id");
                Matcher matcher = ARXIV_ID_PATTERN.matcher(id);
                String arxivId = matcher.find() ? matcher.group(1) : id;
                item.setPaperId("arxiv:" + arxivId);

                String title = getElementText(entry, "title");
                title = title.replaceAll("\\s+", " ").trim();
                item.setTitle(title);
                if (item.getTitle().isBlank()) continue;

                List<String> authors = new ArrayList<>();
                NodeList authorNodes = entry.getElementsByTagName("author");
                for (int j = 0; j < authorNodes.getLength(); j++) {
                    Element authorEl = (Element) authorNodes.item(j);
                    String name = getElementText(authorEl, "name");
                    if (!name.isBlank()) authors.add(name);
                }
                item.setAuthors(authors);

                String published = getElementText(entry, "published");
                if (!published.isBlank() && published.length() >= 4) {
                    try {
                        item.setYear(Integer.parseInt(published.substring(0, 4)));
                    } catch (NumberFormatException ignored) {}
                }

                NodeList categoryNodes = entry.getElementsByTagNameNS("http://arxiv.org/schemas/atom", "primary_category");
                if (categoryNodes.getLength() > 0) {
                    Element catEl = (Element) categoryNodes.item(0);
                    item.setVenue(catEl.getAttribute("term"));
                }

                NodeList links = entry.getElementsByTagName("link");
                for (int j = 0; j < links.getLength(); j++) {
                    Element link = (Element) links.item(j);
                    if ("alternate".equals(link.getAttribute("rel"))) {
                        item.setUrl(link.getAttribute("href"));
                        break;
                    }
                }
                if (item.getUrl() == null || item.getUrl().isBlank()) {
                    item.setUrl(id);
                }

                String summary = getElementText(entry, "summary").replaceAll("\\s+", " ").trim();
                item.setAbstractText(summary);
                item.setEvidenceSnippet(summary);
                item.setRelevanceScore(BigDecimal.valueOf(1.0));
                item.setSource("arxiv");

                result.add(item);
            }
            logger.info("arxiv_search: query='{}', results={}", query, result.size());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("arxiv_search_interrupted: query='{}'", query);
            return List.of();
        } catch (Exception e) {
            logger.warn("arxiv_search_failed: query='{}', error={}", query, e.getMessage());
            return List.of();
        }
    }

    private String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent();
        }
        return "";
    }
}
