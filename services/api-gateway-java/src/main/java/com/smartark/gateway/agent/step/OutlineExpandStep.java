package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.AgentStep;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
public class OutlineExpandStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(OutlineExpandStep.class);
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
        logger.info("Step outline_expand start: taskId={}, sessionId={}, sourceCount={}, ragEvidenceCount={}",
                context.getTask().getId(), session.getId(), sources.size(), ragItems == null ? 0 : ragItems.size());
        JsonNode ragEvidenceNode = (ragItems == null || ragItems.isEmpty())
                ? objectMapper.createArrayNode()
                : objectMapper.valueToTree(ragItems);

        JsonNode expanded = null;
        ObjectNode normalized = null;
        BusinessException lastQualityError = null;
        int maxGenerateAttempts = 3;
        for (int attempt = 1; attempt <= maxGenerateAttempts; attempt++) {
            JsonNode candidateExpanded = modelService.expandPaperOutline(
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
            if (!isExpandedSchemaValid(candidateExpanded)) {
                logger.warn("Step outline_expand schema invalid: taskId={}, sessionId={}, attempt={}/{}",
                        context.getTask().getId(), session.getId(), attempt, maxGenerateAttempts);
                continue;
            }
            ObjectNode candidateNormalized = normalizeExpanded(candidateExpanded, session);
            try {
                ensureManuscriptQualityGate(candidateNormalized, context.getTask().getId(), session.getId());
                expanded = candidateExpanded;
                normalized = candidateNormalized;
                break;
            } catch (BusinessException e) {
                if (e.getCode() != ErrorCodes.TASK_VALIDATION_ERROR) {
                    throw e;
                }
                lastQualityError = e;
                logger.warn("Step outline_expand quality gate retry: taskId={}, sessionId={}, attempt={}/{}, reason={}",
                        context.getTask().getId(), session.getId(), attempt, maxGenerateAttempts, e.getMessage());
            }
        }
        if (normalized == null || expanded == null) {
            if (lastQualityError != null) {
                throw lastQualityError;
            }
            throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, "outline_expand 质量门禁未通过: 模型未生成有效正文");
        }

        String fullCitationMap = buildFullCitationMap(expanded.path("citationMap"), ragItems);
        if (!fullCitationMap.isBlank()) {
            version.setChapterEvidenceMapJson(fullCitationMap);
            context.setChapterEvidenceMapJson(fullCitationMap);
        }

        version.setManuscriptJson(objectMapper.writeValueAsString(normalized));
        paperOutlineVersionRepository.save(version);
        logger.info("Step outline_expand output: taskId={}, sessionId={}, chapterCount={}, sectionCount={}, citationMapCount={}",
                context.getTask().getId(),
                session.getId(),
                normalized.path("chapters").isArray() ? normalized.path("chapters").size() : 0,
                countSections(normalized),
                countCitationMap(fullCitationMap));

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
            ch.put("title", fallback(chapter.path("title").asText(""), chapter.path("chapter").asText("第" + chapterIndex + "章")));
            ch.put("summary", fallback(chapter.path("summary").asText(""), chapter.path("chapter").asText("")));
            ch.put("objective", fallback(chapter.path("objective").asText(""), ""));

            ArrayNode normalizedSections = objectMapper.createArrayNode();
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                ObjectNode sec = objectMapper.createObjectNode();
                sec.put("title", ch.path("title").asText("核心内容"));
                String fallbackContent = fallback(ch.path("summary").asText(""), "本章围绕研究问题展开，结合已检索证据形成可验证论述。");
                sec.put("content", fallbackContent);
                sec.put("coreArgument", fallbackContent);
                sec.put("method", "");
                sec.put("dataPlan", "");
                sec.put("expectedResult", "");
                ArrayNode citations = objectMapper.createArrayNode();
                sec.set("citations", citations);
                normalizedSections.add(sec);
            } else {
                for (JsonNode section : sections) {
                    ObjectNode sec = objectMapper.createObjectNode();
                    sec.put("title", fallback(section.path("title").asText(""), fallback(section.path("section").asText(""), "核心内容")));
                    String synthesizedContent = synthesizeSectionContent(section, chapter);
                    String normalizedCoreArgument = fallback(section.path("coreArgument").asText(""), synthesizedContent);
                    sec.put("coreArgument", normalizedCoreArgument);
                    sec.put("content", fallback(section.path("content").asText(""), fallback(normalizedCoreArgument, synthesizedContent)));
                    sec.put("method", fallback(section.path("method").asText(""), ""));
                    sec.put("dataPlan", fallback(section.path("dataPlan").asText(""), ""));
                    sec.put("expectedResult", fallback(section.path("expectedResult").asText(""), ""));
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

    private String synthesizeSectionContent(JsonNode section, JsonNode chapter) {
        String summary = section.path("summary").asText("");
        if (!summary.isBlank()) {
            return summary;
        }
        StringBuilder sb = new StringBuilder();
        JsonNode subsectionNodes = section.path("subsections");
        if (subsectionNodes.isArray()) {
            for (JsonNode subsection : subsectionNodes) {
                String subsectionTitle = subsection.path("subsection").asText("");
                if (!subsectionTitle.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append("\n");
                    }
                    sb.append(subsectionTitle);
                }
                JsonNode evidenceNodes = subsection.path("evidence");
                if (evidenceNodes.isArray()) {
                    int maxEvidence = Math.min(2, evidenceNodes.size());
                    for (int i = 0; i < maxEvidence; i++) {
                        String evidenceTitle = evidenceNodes.get(i).path("title").asText("");
                        if (!evidenceTitle.isBlank()) {
                            if (!sb.isEmpty()) {
                                sb.append("\n");
                            }
                            sb.append("证据：").append(evidenceTitle);
                        }
                    }
                }
            }
        }
        if (!sb.isEmpty()) {
            return sb.toString();
        }
        return fallback(chapter.path("summary").asText(""), chapter.path("chapter").asText(""));
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

    private int countSections(JsonNode manuscript) {
        JsonNode chapters = manuscript.path("chapters");
        if (!chapters.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode chapter : chapters) {
            JsonNode sections = chapter.path("sections");
            if (sections.isArray()) {
                total += sections.size();
            }
        }
        return total;
    }

    private int countCitationMap(String citationMapJson) {
        if (citationMapJson == null || citationMapJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode node = objectMapper.readTree(citationMapJson);
            return node.isArray() ? node.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void ensureManuscriptQualityGate(ObjectNode normalized, String taskId, Long sessionId) {
        JsonNode chapters = normalized.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty()) {
            throwWithQualityGate("章节为空", taskId, sessionId);
        }

        int sectionCount = 0;
        int placeholderCount = 0;
        int validContentCount = 0;

        for (JsonNode chapter : chapters) {
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                throwWithQualityGate("存在章节没有小节", taskId, sessionId);
            }
            for (JsonNode section : sections) {
                sectionCount++;
                String content = section.path("content").asText("");
                String coreArgument = section.path("coreArgument").asText("");
                String mainText = (content == null || content.isBlank()) ? coreArgument : content;
                if (mainText == null || mainText.isBlank()) {
                    throwWithQualityGate("存在正文与核心论点均为空的小节", taskId, sessionId);
                }
                if (isPlaceholderText(mainText)) {
                    placeholderCount++;
                } else {
                    validContentCount++;
                }
            }
        }

        if (validContentCount == 0) {
            throwWithQualityGate("检测到占位文本 sectionCount=" + sectionCount + ", placeholderCount=" + placeholderCount
                    + ", validContentCount=" + validContentCount, taskId, sessionId);
        }
        if (placeholderCount > 0) {
            logger.warn("Quality gate partial placeholder: taskId={}, sessionId={}, sectionCount={}, placeholderCount={}, validContentCount={}",
                    taskId, sessionId, sectionCount, placeholderCount, validContentCount);
        }
    }

    private boolean isPlaceholderText(String value) {
        if (value == null) {
            return true;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return true;
        }
        return lower.contains("placeholder")
                || lower.contains("this section should be expanded")
                || lower.contains("core content")
                || lower.contains("core argument placeholder")
                || lower.contains("method placeholder")
                || lower.contains("data plan placeholder")
                || lower.contains("expected result placeholder")
                || lower.contains("chapter summary placeholder")
                || lower.contains("chapter objective placeholder")
                || lower.contains("该节暂无正文")
                || lower.contains("待补充")
                || lower.contains("暂无扩写")
                || lower.contains("此处根据证据线索");
    }

    private void throwWithQualityGate(String reason, String taskId, Long sessionId) {
        String message = "outline_expand 质量门禁未通过: " + reason;
        logger.warn("Quality gate failed: taskId={}, sessionId={}, reason={}", taskId, sessionId, reason);
        throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, message);
    }
}
