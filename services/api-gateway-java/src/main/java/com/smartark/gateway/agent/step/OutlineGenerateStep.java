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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class OutlineGenerateStep implements AgentStep {
    private static final Logger logger = LoggerFactory.getLogger(OutlineGenerateStep.class);
    private final ModelService modelService;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperSourceRepository paperSourceRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;
    private final int maxInputChars;
    private final int maxSourceItems;
    private final int maxRagItems;
    private final int minSourceItems;
    private final int minRagItems;

    @Autowired
    public OutlineGenerateStep(ModelService modelService,
                               PaperTopicSessionRepository paperTopicSessionRepository,
                               PaperSourceRepository paperSourceRepository,
                               PaperOutlineVersionRepository paperOutlineVersionRepository,
                               ObjectMapper objectMapper) {
        this(modelService, paperTopicSessionRepository, paperSourceRepository, paperOutlineVersionRepository, objectMapper,
                12000, 20, 15, 6, 4);
    }

    public OutlineGenerateStep(ModelService modelService,
                               PaperTopicSessionRepository paperTopicSessionRepository,
                               PaperSourceRepository paperSourceRepository,
                               PaperOutlineVersionRepository paperOutlineVersionRepository,
                               ObjectMapper objectMapper,
                               @Value("${smartark.paper.outline-generate.max-input-chars:12000}") int maxInputChars,
                               @Value("${smartark.paper.outline-generate.max-source-items:20}") int maxSourceItems,
                               @Value("${smartark.paper.outline-generate.max-rag-items:15}") int maxRagItems,
                               @Value("${smartark.paper.outline-generate.min-source-items:6}") int minSourceItems,
                               @Value("${smartark.paper.outline-generate.min-rag-items:4}") int minRagItems) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
        this.maxInputChars = Math.max(3000, maxInputChars);
        this.maxSourceItems = Math.max(1, maxSourceItems);
        this.maxRagItems = Math.max(1, maxRagItems);
        this.minSourceItems = Math.max(1, minSourceItems);
        this.minRagItems = Math.max(1, minRagItems);
    }

    @Override
    public String getStepCode() {
        return "outline_generate";
    }

    @Override
    public void execute(AgentExecutionContext context) throws Exception {
        PaperTopicSessionEntity session = paperTopicSessionRepository.findByTaskId(context.getTask().getId())
                .orElseThrow();
        List<PaperSourceEntity> sources = paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());

        List<RagEvidenceItem> ragItems = context.getRagEvidenceItems();
        JsonNode outline = generateOutlineWithAdaptiveInput(context, session, sources, ragItems);

        int nextVersion = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(session.getId())
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(session.getId());
        version.setVersionNo(nextVersion);
        version.setCitationStyle("GB/T 7714");
        version.setOutlineJson(objectMapper.writeValueAsString(outline));
        JsonNode evidenceMapping = outline.path("evidenceMapping");
        if (!evidenceMapping.isMissingNode()) {
            String evidenceMapJson = objectMapper.writeValueAsString(evidenceMapping);
            context.setChapterEvidenceMapJson(evidenceMapJson);
            version.setChapterEvidenceMapJson(evidenceMapJson);
        } else if (context.getChapterEvidenceMapJson() != null) {
            version.setChapterEvidenceMapJson(context.getChapterEvidenceMapJson());
        }
        version.setRewriteRound(0);
        version.setCreatedAt(LocalDateTime.now());
        paperOutlineVersionRepository.save(version);

        context.setOutlineDraftJson(objectMapper.writeValueAsString(outline));

        session.setStatus("outlined");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private JsonNode generateOutlineWithAdaptiveInput(AgentExecutionContext context,
                                                      PaperTopicSessionEntity session,
                                                      List<PaperSourceEntity> sources,
                                                      List<RagEvidenceItem> ragItems) {
        List<Plan> plans = buildPlans(sources, ragItems);
        JsonNode lastOutline = null;
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            JsonNode sourceNode = compactSources(sources, plan.sourceLimit(), plan.sourceAbstractMaxLen());
            JsonNode ragNode = compactRagEvidence(ragItems, plan.ragLimit(), plan.ragContentMaxLen());
            int inputChars = estimateInputChars(session, sourceNode, ragNode);
            context.logInfo("Outline generate plan " + (i + 1) + "/" + plans.size()
                    + ": sourceLimit=" + plan.sourceLimit()
                    + ", ragLimit=" + plan.ragLimit()
                    + ", estimatedInputChars=" + inputChars);
            JsonNode outline = modelService.generatePaperOutline(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    session.getTopic(),
                    session.getTopicRefined(),
                    session.getDiscipline(),
                    session.getDegreeLevel(),
                    session.getMethodPreference(),
                    session.getResearchQuestionsJson(),
                    sourceNode,
                    ragNode
            );
            lastOutline = outline;
            if (isOutlineValid(outline)) {
                logger.info("Outline generate quality gate passed: taskId={}, sessionId={}, planIndex={}/{}",
                        context.getTask().getId(), session.getId(), i + 1, plans.size());
                return outline;
            }
            String message = "outline_generate 质量门禁未通过: empty chapters";
            logger.warn("Outline generate quality gate retry: taskId={}, sessionId={}, planIndex={}/{}, sourceLimit={}, ragLimit={}",
                    context.getTask().getId(), session.getId(), i + 1, plans.size(), plan.sourceLimit(), plan.ragLimit());
        }
        String detail = lastOutline == null ? "no_outline_output" : truncateForLog(lastOutline.toString(), 240);
        throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR,
                "outline_generate 质量门禁未通过: 无有效大纲输出, detail=" + detail);
    }

    private List<Plan> buildPlans(List<PaperSourceEntity> sources, List<RagEvidenceItem> ragItems) {
        int sourceCount = sources == null ? 0 : sources.size();
        int ragCount = ragItems == null ? 0 : ragItems.size();
        int fullChars = estimateInputChars(
                null,
                compactSources(sources, sourceCount, 1200),
                compactRagEvidence(ragItems, ragCount, 1500)
        );
        List<Plan> plans = new ArrayList<>();
        plans.add(new Plan(Math.min(sourceCount, maxSourceItems), Math.min(ragCount, maxRagItems), 900, 1100));
        if (fullChars > maxInputChars) {
            plans.add(new Plan(Math.min(sourceCount, Math.max(minSourceItems, maxSourceItems - 6)),
                    Math.min(ragCount, Math.max(minRagItems, maxRagItems - 5)), 600, 700));
            plans.add(new Plan(Math.min(sourceCount, minSourceItems),
                    Math.min(ragCount, minRagItems), 380, 420));
        }
        return deduplicatePlans(plans);
    }

    private List<Plan> deduplicatePlans(List<Plan> plans) {
        List<Plan> dedup = new ArrayList<>();
        for (Plan plan : plans) {
            boolean exists = dedup.stream().anyMatch(p -> p.sourceLimit() == plan.sourceLimit()
                    && p.ragLimit() == plan.ragLimit()
                    && p.sourceAbstractMaxLen() == plan.sourceAbstractMaxLen()
                    && p.ragContentMaxLen() == plan.ragContentMaxLen());
            if (!exists) {
                dedup.add(plan);
            }
        }
        return dedup;
    }

    private int estimateInputChars(PaperTopicSessionEntity session, JsonNode sourceNode, JsonNode ragNode) {
        int base = 0;
        if (session != null) {
            base += safeLen(session.getTopic());
            base += safeLen(session.getTopicRefined());
            base += safeLen(session.getDiscipline());
            base += safeLen(session.getDegreeLevel());
            base += safeLen(session.getMethodPreference());
            base += safeLen(session.getResearchQuestionsJson());
        }
        return base + safeLen(sourceNode == null ? null : sourceNode.toString())
                + safeLen(ragNode == null ? null : ragNode.toString());
    }

    private JsonNode compactSources(List<PaperSourceEntity> sources, int limit, int abstractMaxLen) {
        ArrayNode array = objectMapper.createArrayNode();
        if (sources == null || sources.isEmpty() || limit <= 0) {
            return array;
        }
        List<PaperSourceEntity> sorted = sources.stream()
                .sorted(Comparator.comparing(PaperSourceEntity::getRelevanceScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .toList();
        for (PaperSourceEntity source : sorted) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("paperId", defaultString(source.getPaperId()));
            item.put("title", defaultString(source.getTitle()));
            item.put("year", source.getYear() == null ? 0 : source.getYear());
            item.put("url", defaultString(source.getUrl()));
            item.put("source", defaultString(source.getSource()));
            item.put("evidenceSnippet", truncateForLog(source.getEvidenceSnippet(), 220));
            item.put("abstractText", truncateForLog(source.getAbstractText(), abstractMaxLen));
            array.add(item);
        }
        return array;
    }

    private JsonNode compactRagEvidence(List<RagEvidenceItem> ragItems, int limit, int contentMaxLen) {
        ArrayNode array = objectMapper.createArrayNode();
        if (ragItems == null || ragItems.isEmpty() || limit <= 0) {
            return array;
        }
        List<RagEvidenceItem> sorted = ragItems.stream()
                .sorted(Comparator.comparing(RagEvidenceItem::getRerankScore).reversed())
                .limit(limit)
                .toList();
        for (RagEvidenceItem item : sorted) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("chunkUid", defaultString(item.getChunkUid()));
            node.put("paperId", defaultString(item.getPaperId()));
            node.put("title", defaultString(item.getTitle()));
            node.put("url", defaultString(item.getUrl()));
            node.put("year", item.getYear() == null ? 0 : item.getYear());
            node.put("vectorScore", item.getVectorScore());
            node.put("rerankScore", item.getRerankScore());
            node.put("chunkType", defaultString(item.getChunkType()));
            node.put("content", truncateForLog(item.getContent(), contentMaxLen));
            array.add(node);
        }
        return array;
    }

    private boolean isOutlineValid(JsonNode outline) {
        if (outline == null || !outline.isObject()) {
            return false;
        }
        JsonNode chapters = outline.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty()) {
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
        }
        return true;
    }

    private int safeLen(String value) {
        return value == null ? 0 : value.length();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String truncateForLog(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    private record Plan(int sourceLimit, int ragLimit, int sourceAbstractMaxLen, int ragContentMaxLen) {
    }
}
