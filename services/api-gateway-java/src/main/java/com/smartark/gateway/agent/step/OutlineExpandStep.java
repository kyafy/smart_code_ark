package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OutlineExpandStep implements AgentStep {
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final ObjectMapper objectMapper;

    public OutlineExpandStep(ModelService modelService,
                             PaperTopicSessionRepository paperTopicSessionRepository,
                             PaperOutlineVersionRepository paperOutlineVersionRepository,
                             PaperSourceRepository paperSourceRepository,
                             ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getStepCode() {
        return "outline_expand";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .orElseThrow();
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())
                .stream()
                .filter(s -> s.getPaperId() != null && !s.getPaperId().startsWith("NO_RESULT_"))
                .filter(s -> !"degraded".equalsIgnoreCase(s.getSectionKey()))
                .toList();

        List<RagEvidenceItem> ragItems = context.getRagEvidenceItems();
        JsonNode ragEvidenceNode = (ragItems == null || ragItems.isEmpty())
                ? objectMapper.createArrayNode()
                : objectMapper.valueToTree(ragItems);

        JsonNode expanded = modelService.expandPaperOutline(
                context.getTask().getId(),
                context.getTask().getProjectId(),
                session.getTopic(),
                session.getTopicRefined(),
                session.getDiscipline(),
                session.getDegreeLevel(),
                session.getMethodPreference(),
                session.getResearchQuestionsJson(),
                objectMapper.readTree(version.getOutlineJson()),
                objectMapper.valueToTree(sources),
                ragEvidenceNode
        );
        if (!isExpandedSchemaValid(expanded)) {
            expanded = modelService.expandPaperOutline(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    session.getTopic(),
                    session.getTopicRefined(),
                    session.getDiscipline(),
                    session.getDegreeLevel(),
                    session.getMethodPreference(),
                    session.getResearchQuestionsJson(),
                    objectMapper.readTree(version.getOutlineJson()),
                    objectMapper.valueToTree(sources),
                    ragEvidenceNode
            );
        }
        ObjectNode normalized = normalizeExpanded(expanded, session);

        String fullCitationMap = buildFullCitationMap(expanded.path("citationMap"), ragItems);
        if (!fullCitationMap.isBlank()) {
            version.setChapterEvidenceMapJson(fullCitationMap);
            context.setChapterEvidenceMapJson(fullCitationMap);
        }

        version.setManuscriptJson(objectMapper.writeValueAsString(normalized));
        paperOutlineVersionRepository.save(version);

        context.setExpandedOutlineJson(objectMapper.writeValueAsString(normalized.path("chapters")));
        context.setManuscriptJson(version.getManuscriptJson());
        session.setStatus("expanded");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private boolean isExpandedSchemaValid(JsonNode root) {
        if (root == null || !root.path("chapters").isArray()) {
            return false;
        }
        JsonNode chapters = root.path("chapters");
        if (chapters.isEmpty()) {
            return false;
        }
        for (JsonNode chapter : chapters) {
            if (chapter.path("title").asText("").isBlank()) {
                return false;
            }
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                return false;
            }
            for (JsonNode section : sections) {
                if (section.path("title").asText("").isBlank()) return false;
                if (section.path("coreArgument").asText("").isBlank() && section.path("content").asText("").isBlank()) {
                    return false;
                }
                if (!section.path("citations").isArray()) {
                    return false;
                }
            }
        }
        return true;
    }

    private ObjectNode normalizeExpanded(JsonNode expanded, PaperTopicSessionEntity session) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("topic", session.getTopic() == null ? "" : session.getTopic());
        root.put("topicRefined", session.getTopicRefined() == null ? "" : session.getTopicRefined());
        root.set("researchQuestions", parseQuestions(session.getResearchQuestionsJson()));

        ArrayNode normalizedChapters = objectMapper.createArrayNode();
        JsonNode inputChapters = expanded == null ? objectMapper.createArrayNode() : expanded.path("chapters");
        if (!inputChapters.isArray()) {
            inputChapters = objectMapper.createArrayNode();
        }
        int chapterIndex = 1;
        for (JsonNode chapter : inputChapters) {
            ObjectNode ch = objectMapper.createObjectNode();
            ch.put("index", chapter.path("index").asInt(chapterIndex));
            ch.put("title", fallback(chapter.path("title").asText(""), "Chapter " + chapterIndex));
            ch.put("summary", fallback(chapter.path("summary").asText(""), "Chapter summary placeholder."));
            ch.put("objective", fallback(chapter.path("objective").asText(""), "Chapter objective placeholder."));

            ArrayNode normalizedSections = objectMapper.createArrayNode();
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                ObjectNode sec = objectMapper.createObjectNode();
                sec.put("title", "Core Content");
                sec.put("content", "This section should be expanded with evidence-backed content [1].");
                sec.put("coreArgument", "Core argument placeholder.");
                sec.put("method", "Method placeholder.");
                sec.put("dataPlan", "Data plan placeholder.");
                sec.put("expectedResult", "Expected result placeholder.");
                ArrayNode citations = objectMapper.createArrayNode();
                citations.add(1);
                sec.set("citations", citations);
                normalizedSections.add(sec);
            } else {
                for (JsonNode section : sections) {
                    ObjectNode sec = objectMapper.createObjectNode();
                    sec.put("title", fallback(section.path("title").asText(""), "Core Content"));
                    sec.put("content", fallback(section.path("content").asText(""), ""));
                    sec.put("coreArgument", fallback(section.path("coreArgument").asText(""), fallback(section.path("content").asText(""), "Core argument placeholder.")));
                    sec.put("method", fallback(section.path("method").asText(""), "Method placeholder."));
                    sec.put("dataPlan", fallback(section.path("dataPlan").asText(""), "Data plan placeholder."));
                    sec.put("expectedResult", fallback(section.path("expectedResult").asText(""), "Expected result placeholder."));
                    ArrayNode citations = objectMapper.createArrayNode();
                    JsonNode src = section.path("citations");
                    if (src.isArray() && !src.isEmpty()) {
                        src.forEach(n -> {
                            if (n.isInt() || n.isLong()) {
                                citations.add(n.asInt());
                                return;
                            }
                            String v = n.asText("");
                            if (!v.isBlank() && !v.startsWith("NO_RESULT_")) {
                                try {
                                    citations.add(Integer.parseInt(v));
                                } catch (Exception ignored) {
                                    // skip non numeric citation id
                                }
                            }
                        });
                    }
                    sec.set("citations", citations);
                    normalizedSections.add(sec);
                }
            }
            ch.set("sections", normalizedSections);
            normalizedChapters.add(ch);
            chapterIndex++;
        }
        root.set("chapters", normalizedChapters);
        return root;
    }

    private ArrayNode parseQuestions(String researchQuestionsJson) {
        if (researchQuestionsJson == null || researchQuestionsJson.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode n = objectMapper.readTree(researchQuestionsJson);
            if (n.isArray()) {
                ArrayNode out = objectMapper.createArrayNode();
                List<String> uniq = new ArrayList<>();
                n.forEach(item -> {
                    String v = item.asText("");
                    if (!v.isBlank() && !uniq.contains(v)) {
                        uniq.add(v);
                        out.add(v);
                    }
                });
                return out;
            }
            return objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private String fallback(String value, String def) {
        return value == null || value.isBlank() ? def : value;
    }

    private String buildFullCitationMap(JsonNode citationMapNode, List<RagEvidenceItem> ragItems) {
        if (citationMapNode == null || !citationMapNode.isArray() || citationMapNode.isEmpty()) {
            return "";
        }
        Map<String, RagEvidenceItem> ragIndex = new LinkedHashMap<>();
        if (ragItems != null) {
            for (RagEvidenceItem ragItem : ragItems) {
                if (ragItem == null || ragItem.getChunkUid() == null || ragItem.getChunkUid().isBlank()) {
                    continue;
                }
                ragIndex.putIfAbsent(ragItem.getChunkUid(), ragItem);
            }
        }

        ArrayNode full = objectMapper.createArrayNode();
        for (JsonNode citation : citationMapNode) {
            String chunkUid = citation.path("chunkUid").asText("");
            if (chunkUid.isBlank()) {
                continue;
            }
            RagEvidenceItem rag = ragIndex.get(chunkUid);
            if (rag == null) {
                continue;
            }

            ObjectNode item = objectMapper.createObjectNode();
            item.put("citationIndex", citation.path("citationIndex").asInt());
            item.put("chunkUid", chunkUid);
            item.put("docUid", rag.getDocUid());
            item.put("paperId", rag.getPaperId());
            item.put("title", rag.getTitle());
            item.put("content", rag.getContent());
            item.put("url", rag.getUrl());
            if (rag.getYear() == null) {
                item.putNull("year");
            } else {
                item.put("year", rag.getYear());
            }
            item.put("source", extractSource(rag.getPaperId()));
            item.put("vectorScore", rag.getVectorScore());
            item.put("rerankScore", rag.getRerankScore());
            item.put("chunkType", rag.getChunkType());
            full.add(item);
        }

        try {
            return objectMapper.writeValueAsString(full);
        } catch (Exception e) {
            return "";
        }
    }

    private String extractSource(String paperId) {
        if (paperId == null || paperId.isBlank()) {
            return "unknown";
        }
        int idx = paperId.indexOf(':');
        if (idx <= 0) {
            return "unknown";
        }
        return paperId.substring(0, idx);
    }
}
