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
import com.smartark.gateway.dto.LangchainGraphRunResult;
import com.smartark.gateway.service.LangchainRuntimeGraphClient;
import com.smartark.gateway.service.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final LangchainRuntimeGraphClient runtimeGraphClient;
    private final boolean runtimePaperGraphEnabled;
    private final boolean batchEnabled;
    private final int batchChapterSize;
    private final int batchMaxRetries;
    private final int chapterEvidenceTopK;

    public OutlineExpandStep(ModelService modelService,
                             PaperTopicSessionRepository paperTopicSessionRepository,
                             PaperOutlineVersionRepository paperOutlineVersionRepository,
                             PaperSourceRepository paperSourceRepository,
                             ObjectMapper objectMapper,
                             boolean batchEnabled,
                             int batchChapterSize,
                             int batchMaxRetries,
                             int chapterEvidenceTopK) {
        this(modelService, paperTopicSessionRepository, paperOutlineVersionRepository, paperSourceRepository, objectMapper,
                null, batchEnabled, batchChapterSize, batchMaxRetries, chapterEvidenceTopK, false);
    }

    @Autowired
    public OutlineExpandStep(ModelService modelService,
                             PaperTopicSessionRepository paperTopicSessionRepository,
                             PaperOutlineVersionRepository paperOutlineVersionRepository,
                             PaperSourceRepository paperSourceRepository,
                             ObjectMapper objectMapper,
                             LangchainRuntimeGraphClient runtimeGraphClient,
                             @Value("${smartark.paper.expand.batch-enabled:true}") boolean batchEnabled,
                             @Value("${smartark.paper.expand.batch-chapter-size:2}") int batchChapterSize,
                             @Value("${smartark.paper.expand.batch-max-retries:2}") int batchMaxRetries,
                             @Value("${smartark.paper.expand.chapter-evidence-topk:8}") int chapterEvidenceTopK,
                             @Value("${smartark.langchain.runtime.paper-graph-enabled:false}") boolean runtimePaperGraphEnabled) {
        this.modelService = modelService;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.paperSourceRepository = paperSourceRepository;
        this.objectMapper = objectMapper;
        this.runtimeGraphClient = runtimeGraphClient;
        this.runtimePaperGraphEnabled = runtimePaperGraphEnabled;
        this.batchEnabled = batchEnabled;
        this.batchChapterSize = Math.max(1, batchChapterSize);
        this.batchMaxRetries = Math.max(0, batchMaxRetries);
        this.chapterEvidenceTopK = Math.max(1, chapterEvidenceTopK);
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
        context.logInfo("Step outline_expand start: batchEnabled=" + batchEnabled
                + ", sourceCount=" + sources.size()
                + ", ragEvidenceCount=" + (ragItems == null ? 0 : ragItems.size()));
        JsonNode ragEvidenceNode = (ragItems == null || ragItems.isEmpty())
                ? objectMapper.createArrayNode()
                : objectMapper.valueToTree(ragItems);

        ObjectNode normalized;
        String fullCitationMap;
        JsonNode outlineRoot = objectMapper.readTree(version.getOutlineJson());
        JsonNode flattenedOutline = flattenOutlineForExpand(outlineRoot);
        StructureExpectation structureExpectation = buildStructureExpectation(flattenedOutline.path("chapters"));
        Map<Integer, Integer> expectedSectionCounts = structureExpectation.expectedSectionCountPerChapter();
        int totalFlatSections = structureExpectation.expectedSectionCountTotal();
        validateStructureExpectation(structureExpectation, context.getTask().getId());
        logGuardProbe(context, "precheck", structureExpectation, null, "allow_write", "none", null, null);
        context.logInfo("Outline flattened: originalSections=" + countOutlineSections(outlineRoot)
                + ", flattenedSections=" + totalFlatSections);
        ExpandResult runtimeExpandResult = executeRuntimeExpand(context, session, flattenedOutline, ragItems, structureExpectation);
        if (runtimeExpandResult != null) {
            normalized = runtimeExpandResult.normalized();
            fullCitationMap = runtimeExpandResult.citationMapJson();
            context.logInfo("Outline expand routed to langchain runtime graph successfully.");
        } else if (batchEnabled) {
            List<BatchPlanItem> plans = planBatches(flattenedOutline.path("chapters"));
            logger.info("Step outline_expand batch plan: taskId={}, sessionId={}, totalChapters={}, batchCount={}",
                    context.getTask().getId(), session.getId(), flattenedOutline.path("chapters").size(), plans.size());
            context.logInfo("Batch plan: totalChapters=" + flattenedOutline.path("chapters").size()
                    + ", batchCount=" + plans.size()
                    + ", batchChapterSize=" + batchChapterSize);
            List<BatchExpandResult> batchResults = new ArrayList<>();
            for (int bi = 0; bi < plans.size(); bi++) {
                BatchPlanItem plan = plans.get(bi);
                context.logInfo("Batch " + (bi + 1) + "/" + plans.size()
                        + " start: chapterRange=" + (plan.startIndex() + 1) + "-" + (plan.endIndex() + 1));
                BatchExpandResult batchResult = executeBatchExpand(context, session, plan, structureExpectation.expectedChapterCount(), ragItems, expectedSectionCounts);
                context.logInfo("Batch " + (bi + 1) + "/" + plans.size()
                        + " done: chapters=" + (batchResult.chapters() == null ? 0 : batchResult.chapters().size())
                        + ", citations=" + (batchResult.citationMap() == null ? 0 : batchResult.citationMap().size()));
                batchResults.add(batchResult);
            }
            MergeResult merged = mergeBatchResults(batchResults, ragItems);
            context.logInfo("Batch merge done: mergedChapters=" + merged.normalized().path("chapters").size()
                    + ", citationMapLength=" + merged.citationMapJson().length());
            normalized = merged.normalized();
            normalized.put("topic", session.getTopic());
            normalized.put("topicRefined", session.getTopicRefined());
            normalized.set("researchQuestions", parseJsonArray(session.getResearchQuestionsJson()));
            fullCitationMap = merged.citationMapJson();
        } else {
            ExpandResult expandResult = executeSingleExpand(context, session, flattenedOutline, sources, ragEvidenceNode, ragItems, structureExpectation);
            normalized = expandResult.normalized();
            fullCitationMap = expandResult.citationMapJson();
        }
        ensureManuscriptQualityGate(normalized, context.getTask().getId(), session.getId());
        StructureCoverage coverage = evaluateStructureCoverage(normalized.path("chapters"), structureExpectation);
        if (!isPreCommitPass(coverage, structureExpectation)) {
            String message = "outline_expand 写回前复检未通过: chapterCoverage="
                    + formatCoverage(coverage.chapterCoverage())
                    + ", sectionCoverage=" + formatCoverage(coverage.sectionCoverage());
            logGuardProbe(context, "precommit", structureExpectation, coverage, "block_write", "task_failed",
                    "PAPER_PRECOMMIT_COVERAGE_FAILED", message);
            throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, message);
        }
        logGuardProbe(context, "precommit", structureExpectation, coverage, "allow_write", "none", null, null);
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
        context.logInfo("Step outline_expand output: chapterCount="
                + (normalized.path("chapters").isArray() ? normalized.path("chapters").size() : 0)
                + ", sectionCount=" + countSections(normalized)
                + ", citationMapCount=" + countCitationMap(fullCitationMap));
        session.setStatus("expanded");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);
    }

    private ExpandResult executeRuntimeExpand(AgentExecutionContext context,
                                              PaperTopicSessionEntity session,
                                              JsonNode flattenedOutline,
                                              List<RagEvidenceItem> ragItems,
                                              StructureExpectation structureExpectation) {
        if (!runtimePaperGraphEnabled || runtimeGraphClient == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("stage", "outline_expand");
            input.put("topic", fallback(session.getTopic(), ""));
            input.put("topicRefined", fallback(session.getTopicRefined(), ""));
            input.put("discipline", fallback(session.getDiscipline(), ""));
            input.put("degreeLevel", fallback(session.getDegreeLevel(), ""));
            input.put("methodPreference", fallback(session.getMethodPreference(), ""));
            input.put("researchQuestionsJson", fallback(session.getResearchQuestionsJson(), "[]"));
            input.put("outline", objectMapper.convertValue(flattenedOutline, Object.class));
            input.put("ragEvidence", objectMapper.convertValue(ragItems == null ? List.of() : ragItems, Object.class));
            input.put("expectedChapterCount", structureExpectation.expectedChapterCount());
            input.put("expectedSectionCountPerChapter", structureExpectation.expectedSectionCountPerChapter());

            LangchainGraphRunResult graphResult = runtimeGraphClient.runPaperGraph(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    context.getTask().getUserId(),
                    input
            );
            JsonNode resultNode = objectMapper.valueToTree(graphResult == null ? Map.of() : graphResult.result());
            JsonNode expanded = adaptRuntimeExpanded(resultNode, flattenedOutline, structureExpectation.expectedSectionCountPerChapter());
            if (!isExpandedSchemaValid(expanded, structureExpectation.expectedChapterCount(),
                    structureExpectation.expectedSectionCountPerChapter())) {
                logger.warn("Runtime graph returned invalid expanded shape, fallback to model-service: taskId={}, sessionId={}",
                        context.getTask().getId(), session.getId());
                return null;
            }

            ObjectNode normalized = normalizeExpanded(expanded, session);
            ensureManuscriptQualityGate(normalized, context.getTask().getId(), session.getId());
            logger.info("Outline expand generated by runtime graph: taskId={}, sessionId={}",
                    context.getTask().getId(), session.getId());
            return new ExpandResult(normalized, buildFullCitationMap(expanded.path("citationMap"), ragItems));
        } catch (Exception e) {
            logger.warn("Runtime graph expand failed, fallback to model-service: taskId={}, sessionId={}, error={}",
                    context.getTask().getId(), session.getId(), e.getMessage());
            return null;
        }
    }

    private ExpandResult executeSingleExpand(AgentExecutionContext context,
                                             PaperTopicSessionEntity session,
                                             JsonNode flattenedOutline,
                                             List<PaperSourceEntity> sources,
                                             JsonNode ragEvidenceNode,
                                             List<RagEvidenceItem> ragItems,
                                             StructureExpectation structureExpectation) {
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
                    flattenedOutline,
                    objectMapper.valueToTree(sources),
                    ragEvidenceNode
            );
            if (!isExpandedSchemaValid(candidateExpanded, structureExpectation.expectedChapterCount(),
                    structureExpectation.expectedSectionCountPerChapter())) {
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
        return new ExpandResult(normalized, buildFullCitationMap(expanded.path("citationMap"), ragItems));
    }

    private List<BatchPlanItem> planBatches(JsonNode chaptersNode) {
        List<JsonNode> chapters = new ArrayList<>();
        if (chaptersNode != null && chaptersNode.isArray()) {
            chaptersNode.forEach(chapters::add);
        }
        List<BatchPlanItem> plans = new ArrayList<>();
        int index = 0;
        int batchNo = 1;
        while (index < chapters.size()) {
            int currentSize = batchChapterSize;
            int estimatedTokens = estimateChapterTokens(chapters.get(index));
            if (estimatedTokens > 2000) {
                currentSize = 1;
            }
            int end = Math.min(chapters.size(), index + currentSize);
            List<JsonNode> batchChapters = new ArrayList<>(chapters.subList(index, end));
            plans.add(new BatchPlanItem(batchNo++, index, end - 1, batchChapters));
            index = end;
        }
        return plans;
    }

    private BatchExpandResult executeBatchExpand(AgentExecutionContext context,
                                                 PaperTopicSessionEntity session,
                                                 BatchPlanItem plan,
                                                 int totalChapters,
                                                 List<RagEvidenceItem> ragItems,
                                                 Map<Integer, Integer> expectedSectionCounts) throws Exception {
        JsonNode batchOutlineJson = objectMapper.createObjectNode().set("chapters", objectMapper.valueToTree(plan.chapters()));
        JsonNode batchEvidence = selectEvidenceForBatch(plan.chapters(), ragItems);
        BusinessException lastError = null;
        for (int attempt = 0; attempt <= batchMaxRetries; attempt++) {
            JsonNode expanded = modelService.expandPaperOutlineBatch(
                    context.getTask().getId(),
                    context.getTask().getProjectId(),
                    session.getTopic(),
                    session.getTopicRefined(),
                    session.getDiscipline(),
                    session.getDegreeLevel(),
                    session.getMethodPreference(),
                    session.getResearchQuestionsJson(),
                    batchOutlineJson,
                    batchEvidence,
                    (plan.startIndex() + 1) + "-" + (plan.endIndex() + 1),
                    totalChapters
            );
            Map<Integer, Integer> batchExpectedCounts = new LinkedHashMap<>();
            for (int ci = plan.startIndex(); ci <= plan.endIndex(); ci++) {
                Integer expected = expectedSectionCounts.get(ci);
                if (expected != null) {
                    batchExpectedCounts.put(ci - plan.startIndex(), expected);
                }
            }
            if (!isExpandedSchemaValid(expanded, plan.chapters().size(), batchExpectedCounts)) {
                logger.warn("Batch expand schema invalid: taskId={}, sessionId={}, batchNo={}, attempt={}/{}",
                        context.getTask().getId(), session.getId(), plan.batchNo(), attempt + 1, batchMaxRetries + 1);
                continue;
            }
            ObjectNode normalized = normalizeExpanded(expanded, session);
            try {
                ensureManuscriptQualityGate(normalized, context.getTask().getId(), session.getId());
                logger.info("Batch expand success: taskId={}, sessionId={}, batchNo={}, chapterRange={}, chapterCount={}, evidenceCount={}",
                        context.getTask().getId(), session.getId(), plan.batchNo(),
                        (plan.startIndex() + 1) + "-" + (plan.endIndex() + 1),
                        normalized.path("chapters").size(), batchEvidence.size());
                return new BatchExpandResult(normalized.path("chapters"), expanded.path("citationMap"));
            } catch (BusinessException e) {
                if (e.getCode() != ErrorCodes.TASK_VALIDATION_ERROR) {
                    throw e;
                }
                lastError = e;
                logger.warn("Batch expand quality gate retry: taskId={}, sessionId={}, batchNo={}, attempt={}/{}, reason={}",
                        context.getTask().getId(), session.getId(), plan.batchNo(), attempt + 1, batchMaxRetries + 1, e.getMessage());
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, "outline_expand 批次扩写失败: batchNo=" + plan.batchNo());
    }

    private JsonNode selectEvidenceForBatch(List<JsonNode> batchChapters, List<RagEvidenceItem> ragItems) {
        if (ragItems == null || ragItems.isEmpty()) {
            return objectMapper.createArrayNode();
        }
        List<String> keywords = new ArrayList<>();
        for (JsonNode chapter : batchChapters) {
            addKeyword(keywords, chapter.path("title").asText(""));
            JsonNode sections = chapter.path("sections");
            if (sections.isArray()) {
                for (JsonNode section : sections) {
                    addKeyword(keywords, section.path("title").asText(""));
                    JsonNode subsections = section.path("subsections");
                    if (subsections.isArray()) {
                        for (JsonNode subsection : subsections) {
                            addKeyword(keywords, subsection.path("subsection").asText(""));
                        }
                    }
                }
            }
        }
        List<RagEvidenceItem> ranked = new ArrayList<>(ragItems);
        ranked.sort(Comparator.comparingInt((RagEvidenceItem item) -> -matchScore(item, keywords))
                .thenComparingDouble(RagEvidenceItem::getRerankScore).reversed());
        int limit = Math.min(ranked.size(), Math.max(5, chapterEvidenceTopK * Math.max(1, batchChapters.size())));
        return objectMapper.valueToTree(ranked.subList(0, limit));
    }

    private MergeResult mergeBatchResults(List<BatchExpandResult> batchResults, List<RagEvidenceItem> ragItems) {
        ArrayNode mergedChapters = objectMapper.createArrayNode();
        ArrayNode mergedCitationMap = objectMapper.createArrayNode();
        Map<String, Integer> chunkToNewIndex = new LinkedHashMap<>();
        Map<String, RagEvidenceItem> ragIndex = new LinkedHashMap<>();
        if (ragItems != null) {
            for (RagEvidenceItem item : ragItems) {
                if (item != null && item.getChunkUid() != null && !item.getChunkUid().isBlank()) {
                    ragIndex.putIfAbsent(item.getChunkUid(), item);
                }
            }
        }
        int nextIndex = 1;
        for (BatchExpandResult batchResult : batchResults) {
            Map<Integer, Integer> oldToNew = new LinkedHashMap<>();
            JsonNode citationMap = batchResult.citationMap();
            if (citationMap != null && citationMap.isArray()) {
                for (JsonNode citation : citationMap) {
                    int oldIndex = citation.path("citationIndex").asInt();
                    String chunkUid = citation.path("chunkUid").asText("");
                    if (oldIndex <= 0 || chunkUid.isBlank()) {
                        continue;
                    }
                    Integer newIndex = chunkToNewIndex.get(chunkUid);
                    if (newIndex == null) {
                        newIndex = nextIndex++;
                        chunkToNewIndex.put(chunkUid, newIndex);
                        RagEvidenceItem rag = ragIndex.get(chunkUid);
                        ObjectNode item = objectMapper.createObjectNode();
                        item.put("citationIndex", newIndex);
                        item.put("chunkUid", chunkUid);
                        if (rag != null) {
                            item.put("docUid", rag.getDocUid());
                            item.put("paperId", rag.getPaperId());
                            item.put("title", rag.getTitle());
                            item.put("content", rag.getContent());
                            item.put("url", rag.getUrl());
                            if (rag.getYear() == null) item.putNull("year"); else item.put("year", rag.getYear());
                            item.put("source", extractSource(rag.getPaperId()));
                            item.put("vectorScore", rag.getVectorScore());
                            item.put("rerankScore", rag.getRerankScore());
                            item.put("chunkType", rag.getChunkType());
                        }
                        mergedCitationMap.add(item);
                    }
                    oldToNew.put(oldIndex, newIndex);
                }
            }
            JsonNode chapters = batchResult.chapters();
            if (chapters != null && chapters.isArray()) {
                for (JsonNode chapterNode : chapters) {
                    ObjectNode chapter = chapterNode.deepCopy();
                    JsonNode sections = chapter.path("sections");
                    if (sections.isArray()) {
                        for (JsonNode sectionNode : sections) {
                            ObjectNode section = (ObjectNode) sectionNode;
                            ArrayNode newCitations = objectMapper.createArrayNode();
                            JsonNode oldCitations = section.path("citations");
                            if (oldCitations.isArray()) {
                                for (JsonNode citation : oldCitations) {
                                    Integer mapped = oldToNew.get(citation.asInt());
                                    if (mapped != null) {
                                        newCitations.add(mapped);
                                    }
                                }
                            }
                            section.set("citations", newCitations);
                        }
                    }
                    mergedChapters.add(chapter);
                }
            }
        }
        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("topic", "");
        merged.put("topicRefined", "");
        merged.set("researchQuestions", objectMapper.createArrayNode());
        merged.set("chapters", mergedChapters);
        try {
            return new MergeResult(merged, objectMapper.writeValueAsString(mergedCitationMap));
        } catch (Exception e) {
            return new MergeResult(merged, "");
        }
    }

    private JsonNode adaptRuntimeExpanded(JsonNode resultNode,
                                          JsonNode flattenedOutline,
                                          Map<Integer, Integer> expectedSectionCounts) {
        if (resultNode == null || resultNode.isNull()) {
            return null;
        }
        JsonNode expandedJson = resultNode.path("expanded_json");
        if (isRuntimeExpandedNode(expandedJson)) {
            return ensureCitationMap(expandedJson);
        }
        JsonNode manuscriptJson = resultNode.path("manuscript_json");
        if (isRuntimeExpandedNode(manuscriptJson)) {
            return ensureCitationMap(manuscriptJson);
        }
        JsonNode chaptersNode = resultNode.path("chapters");
        if (chaptersNode.isArray()) {
            return wrapRuntimeChapters(chaptersNode, flattenedOutline, expectedSectionCounts, resultNode.path("citationMap"));
        }
        return null;
    }

    private boolean isRuntimeExpandedNode(JsonNode node) {
        return node != null && node.isObject() && node.path("chapters").isArray();
    }

    private JsonNode ensureCitationMap(JsonNode expandedNode) {
        if (expandedNode == null || !expandedNode.isObject()) {
            return expandedNode;
        }
        ObjectNode root = expandedNode.deepCopy();
        if (!root.path("citationMap").isArray()) {
            root.set("citationMap", objectMapper.createArrayNode());
        }
        return root;
    }

    private JsonNode wrapRuntimeChapters(JsonNode runtimeChapters,
                                         JsonNode flattenedOutline,
                                         Map<Integer, Integer> expectedSectionCounts,
                                         JsonNode runtimeCitationMap) {
        ArrayNode normalizedChapters = objectMapper.createArrayNode();
        JsonNode expectedChapters = flattenedOutline == null ? objectMapper.createArrayNode() : flattenedOutline.path("chapters");
        int chapterCount = expectedChapters.isArray() ? expectedChapters.size() : runtimeChapters.size();
        for (int ci = 0; ci < chapterCount; ci++) {
            JsonNode expectedChapter = expectedChapters.isArray() && ci < expectedChapters.size()
                    ? expectedChapters.get(ci)
                    : objectMapper.createObjectNode();
            JsonNode runtimeChapter = ci < runtimeChapters.size() ? runtimeChapters.get(ci) : objectMapper.createObjectNode();
            ObjectNode chapter = objectMapper.createObjectNode();
            chapter.put("index", runtimeChapter.path("index").asInt(ci + 1));
            chapter.put("title", fallback(runtimeChapter.path("title").asText(""),
                    fallback(expectedChapter.path("title").asText(""), "Chapter " + (ci + 1))));
            chapter.put("summary", fallback(runtimeChapter.path("summary").asText(""), expectedChapter.path("summary").asText("")));
            chapter.put("objective", fallback(runtimeChapter.path("objective").asText(""), ""));

            ArrayNode sections = objectMapper.createArrayNode();
            JsonNode runtimeSections = runtimeChapter.path("sections");
            JsonNode expectedSections = expectedChapter.path("sections");
            int expectedCount = expectedSectionCounts == null ? 0 : expectedSectionCounts.getOrDefault(ci, 0);
            int runtimeCount = runtimeSections.isArray() ? runtimeSections.size() : 0;
            int sectionCount = Math.max(Math.max(1, expectedCount), runtimeCount);
            for (int si = 0; si < sectionCount; si++) {
                JsonNode runtimeSection = runtimeSections.isArray() && si < runtimeSections.size()
                        ? runtimeSections.get(si)
                        : objectMapper.createObjectNode();
                JsonNode expectedSection = expectedSections.isArray() && si < expectedSections.size()
                        ? expectedSections.get(si)
                        : objectMapper.createObjectNode();
                ObjectNode section = objectMapper.createObjectNode();
                String sectionTitle = fallback(runtimeSection.path("title").asText(""),
                        fallback(runtimeSection.path("section").asText(""),
                                fallback(expectedSection.path("title").asText(""), "Section " + (ci + 1) + "." + (si + 1))));
                String content = fallback(runtimeSection.path("content").asText(""),
                        fallback(runtimeSection.path("summary").asText(""),
                                "This section elaborates on " + sectionTitle + " based on the current evidence."));
                String coreArgument = fallback(runtimeSection.path("coreArgument").asText(""), content);
                section.put("title", sectionTitle);
                section.put("content", content);
                section.put("coreArgument", coreArgument);
                section.put("method", fallback(runtimeSection.path("method").asText(""), ""));
                section.put("dataPlan", fallback(runtimeSection.path("dataPlan").asText(""), ""));
                section.put("expectedResult", fallback(runtimeSection.path("expectedResult").asText(""), ""));
                section.set("citations", normalizeCitations(runtimeSection.path("citations")));
                sections.add(section);
            }

            chapter.set("sections", sections);
            normalizedChapters.add(chapter);
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.set("chapters", normalizedChapters);
        if (runtimeCitationMap != null && runtimeCitationMap.isArray()) {
            root.set("citationMap", runtimeCitationMap);
        } else {
            root.set("citationMap", objectMapper.createArrayNode());
        }
        return root;
    }

    private ArrayNode normalizeCitations(JsonNode citationsNode) {
        ArrayNode citations = objectMapper.createArrayNode();
        if (citationsNode == null || !citationsNode.isArray()) {
            return citations;
        }
        for (JsonNode item : citationsNode) {
            if (item.isInt() || item.isLong()) {
                citations.add(item.asInt());
                continue;
            }
            String value = item.asText("");
            if (value.isBlank()) {
                continue;
            }
            try {
                citations.add(Integer.parseInt(value));
            } catch (Exception ignored) {
                // ignore non-numeric citation values
            }
        }
        return citations;
    }

    private JsonNode readJson(String text) {
        try {
            if (text == null || text.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode parseJsonArray(String text) {
        try {
            if (text == null || text.isBlank()) {
                return objectMapper.createArrayNode();
            }
            JsonNode node = objectMapper.readTree(text);
            return node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception e) {
            return objectMapper.createArrayNode();
        }
    }

    private int estimateChapterTokens(JsonNode chapter) {
        try {
            return Math.max(1, objectMapper.writeValueAsString(chapter).length() / 4);
        } catch (Exception e) {
            return 1;
        }
    }

    private void addKeyword(List<String> keywords, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (!keywords.contains(normalized)) {
            keywords.add(normalized);
        }
    }

    private int matchScore(RagEvidenceItem item, List<String> keywords) {
        String text = ((item.getTitle() == null ? "" : item.getTitle()) + " " + (item.getContent() == null ? "" : item.getContent())).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : keywords) {
            if (!keyword.isBlank() && text.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private record ExpandResult(ObjectNode normalized, String citationMapJson) {
    }

    private record BatchPlanItem(int batchNo, int startIndex, int endIndex, List<JsonNode> chapters) {
    }

    private record BatchExpandResult(JsonNode chapters, JsonNode citationMap) {
    }

    private record MergeResult(ObjectNode normalized, String citationMapJson) {
    }

    /**
     * 将三级大纲（chapters → sections → subsections）扁平化为二级（chapters → sections）。
     * 如果 section 有 subsections，则每个 subsection 提升为独立 section，标题格式为 "sectionTitle — subsectionTitle"。
     * 如果 section 没有 subsections，则保持原样。
     */
    private JsonNode flattenOutlineForExpand(JsonNode outlineRoot) {
        if (outlineRoot == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode chapters = outlineRoot.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty()) {
            return outlineRoot.deepCopy();
        }
        ObjectNode result = outlineRoot.deepCopy();
        ArrayNode flattenedChapters = objectMapper.createArrayNode();
        for (JsonNode chapter : chapters) {
            ObjectNode flatChapter = chapter.deepCopy();
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                flattenedChapters.add(flatChapter);
                continue;
            }
            ArrayNode flatSections = objectMapper.createArrayNode();
            for (JsonNode section : sections) {
                String sectionTitle = section.path("title").asText(section.path("section").asText(""));
                JsonNode subsections = section.path("subsections");
                if (subsections.isArray() && !subsections.isEmpty()) {
                    for (JsonNode sub : subsections) {
                        String subTitle = sub.path("subsection").asText(sub.path("title").asText(""));
                        ObjectNode flatSection = objectMapper.createObjectNode();
                        String combinedTitle = sectionTitle.isBlank() ? subTitle
                                : subTitle.isBlank() ? sectionTitle
                                : sectionTitle + " — " + subTitle;
                        flatSection.put("title", combinedTitle);
                        // carry over evidence from subsection if present
                        if (sub.has("evidence")) {
                            flatSection.set("evidence", sub.path("evidence"));
                        }
                        // carry over evidenceMapping from parent section if present
                        if (section.has("evidenceMapping")) {
                            flatSection.set("evidenceMapping", section.path("evidenceMapping"));
                        }
                        flatSections.add(flatSection);
                    }
                } else {
                    // no subsections — keep section as-is (remove subsections field to avoid confusion)
                    ObjectNode flatSection = section.deepCopy();
                    flatSection.remove("subsections");
                    flatSections.add(flatSection);
                }
            }
            flatChapter.set("sections", flatSections);
            flattenedChapters.add(flatChapter);
        }
        result.set("chapters", flattenedChapters);
        return result;
    }

    /**
     * 构建每章期望的 section 数量映射（key=章索引从0开始，value=该章期望的 section 数量）。
     */
    private Map<Integer, Integer> buildExpectedSectionCounts(JsonNode flattenedChapters) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        if (flattenedChapters == null || !flattenedChapters.isArray()) {
            return counts;
        }
        int index = 0;
        for (JsonNode chapter : flattenedChapters) {
            JsonNode sections = chapter.path("sections");
            counts.put(index, sections.isArray() ? sections.size() : 0);
            index++;
        }
        return counts;
    }

    private int countOutlineSections(JsonNode outlineRoot) {
        JsonNode chapters = outlineRoot.path("chapters");
        if (!chapters.isArray()) return 0;
        int count = 0;
        for (JsonNode ch : chapters) {
            JsonNode sections = ch.path("sections");
            if (sections.isArray()) count += sections.size();
        }
        return count;
    }

    private boolean isExpandedSchemaValid(JsonNode root, int expectedChapterCount, Map<Integer, Integer> expectedSectionCounts) {
        if (root == null || !root.path("chapters").isArray()) {
            logger.warn("Structure mismatch reason=invalid_chapters_root");
            return false;
        }
        JsonNode chapters = root.path("chapters");
        if (chapters.isEmpty()) {
            logger.warn("Structure mismatch reason=empty_chapters");
            return false;
        }
        if (expectedChapterCount > 0 && chapters.size() != expectedChapterCount) {
            logger.warn("Structure mismatch reason=chapter_mismatch expected={}, actual={}",
                    expectedChapterCount, chapters.size());
            return false;
        }
        int chapterIdx = 0;
        for (JsonNode chapter : chapters) {
            if (chapter.path("title").asText("").isBlank()) {
                logger.warn("Structure mismatch reason=blank_chapter_title chapterIndex={}", chapterIdx);
                return false;
            }
            JsonNode sections = chapter.path("sections");
            if (!sections.isArray() || sections.isEmpty()) {
                logger.warn("Structure mismatch reason=empty_sections chapterIndex={}", chapterIdx);
                return false;
            }
            // 校验 section 数量与扁平化后的大纲一致
            if (expectedSectionCounts != null && !expectedSectionCounts.isEmpty()) {
                Integer expected = expectedSectionCounts.get(chapterIdx);
                if (expected == null) {
                    logger.warn("Structure mismatch reason=missing_expected_section chapterIndex={}", chapterIdx);
                    return false;
                }
                if (expected > 0 && sections.size() < expected) {
                    logger.warn("Structure mismatch reason=section_mismatch chapter={}, expected={}, actual={}",
                            chapter.path("title").asText(""), expected, sections.size());
                    return false;
                }
            }
            for (JsonNode section : sections) {
                if (section.path("title").asText("").isBlank()) {
                    logger.warn("Structure mismatch reason=blank_section_title chapterIndex={}", chapterIdx);
                    return false;
                }
                if (section.path("coreArgument").asText("").isBlank() && section.path("content").asText("").isBlank()) {
                    logger.warn("Structure mismatch reason=empty_section_content chapterIndex={}", chapterIdx);
                    return false;
                }
                if (!section.path("citations").isArray()) {
                    logger.warn("Structure mismatch reason=invalid_section_citations chapterIndex={}", chapterIdx);
                    return false;
                }
            }
            chapterIdx++;
        }
        return true;
    }

    private StructureExpectation buildStructureExpectation(JsonNode flattenedChapters) {
        Map<Integer, Integer> expectedSections = buildExpectedSectionCounts(flattenedChapters);
        int expectedChapterCount = flattenedChapters != null && flattenedChapters.isArray() ? flattenedChapters.size() : 0;
        int expectedSectionCountTotal = expectedSections.values().stream().mapToInt(Integer::intValue).sum();
        return new StructureExpectation(expectedChapterCount, expectedSections, expectedSectionCountTotal);
    }

    private void validateStructureExpectation(StructureExpectation expectation, String taskId) {
        boolean invalid = expectation.expectedChapterCount() <= 0
                || expectation.expectedSectionCountTotal() <= 0
                || expectation.expectedSectionCountPerChapter().size() != expectation.expectedChapterCount()
                || expectation.expectedSectionCountPerChapter().values().stream().anyMatch(count -> count == null || count <= 0);
        if (!invalid) {
            return;
        }
        String message = "outline_expand 生成前约束输入无效";
        logGuardProbe(taskId, "outline_expand", "precheck", expectation.expectedChapterCount(), expectation.expectedSectionCountTotal(),
                null, null, "block_write", "task_failed", "PAPER_PRECHECK_INVALID_INPUT", message);
        throw new BusinessException(ErrorCodes.TASK_VALIDATION_ERROR, message);
    }

    private StructureCoverage evaluateStructureCoverage(JsonNode manuscriptChapters, StructureExpectation expectation) {
        int generatedChapterCount = manuscriptChapters != null && manuscriptChapters.isArray() ? manuscriptChapters.size() : 0;
        int generatedSectionCount = 0;
        int matchedSectionCount = 0;
        if (manuscriptChapters != null && manuscriptChapters.isArray()) {
            int chapterIndex = 0;
            for (JsonNode chapter : manuscriptChapters) {
                JsonNode sections = chapter.path("sections");
                int actualSections = sections.isArray() ? sections.size() : 0;
                generatedSectionCount += actualSections;
                Integer expectedSections = expectation.expectedSectionCountPerChapter().get(chapterIndex);
                if (expectedSections != null && expectedSections > 0) {
                    matchedSectionCount += Math.min(actualSections, expectedSections);
                }
                chapterIndex++;
            }
        }
        double chapterCoverage = expectation.expectedChapterCount() <= 0
                ? 0D
                : (double) generatedChapterCount / (double) expectation.expectedChapterCount();
        double sectionCoverage = expectation.expectedSectionCountTotal() <= 0
                ? 0D
                : (double) matchedSectionCount / (double) expectation.expectedSectionCountTotal();
        return new StructureCoverage(generatedChapterCount, generatedSectionCount, matchedSectionCount, chapterCoverage, sectionCoverage);
    }

    private boolean isPreCommitPass(StructureCoverage coverage, StructureExpectation expectation) {
        return coverage.generatedChapterCount() == expectation.expectedChapterCount()
                && coverage.matchedSectionCount() == expectation.expectedSectionCountTotal()
                && coverage.chapterCoverage() >= 1.0D
                && coverage.sectionCoverage() >= 1.0D;
    }

    private String formatCoverage(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private void logGuardProbe(AgentExecutionContext context,
                               String guardStage,
                               StructureExpectation expectation,
                               StructureCoverage coverage,
                               String decision,
                               String fallbackAction,
                               String errorCode,
                               String errorMessage) {
        logGuardProbe(context.getTask().getId(), "outline_expand", guardStage,
                expectation.expectedChapterCount(), expectation.expectedSectionCountTotal(),
                coverage == null ? null : coverage.chapterCoverage(),
                coverage == null ? null : coverage.sectionCoverage(),
                decision, fallbackAction, errorCode, errorMessage);
        if (context != null) {
            context.logInfo("paper_guard taskId=" + context.getTask().getId()
                    + ", stepCode=outline_expand"
                    + ", guardStage=" + guardStage
                    + ", chapterCoverage=" + (coverage == null ? "na" : formatCoverage(coverage.chapterCoverage()))
                    + ", sectionCoverage=" + (coverage == null ? "na" : formatCoverage(coverage.sectionCoverage()))
                    + ", decision=" + decision
                    + ", fallbackAction=" + fallbackAction
                    + ", errorCode=" + (errorCode == null ? "none" : errorCode));
        }
    }

    private void logGuardProbe(String taskId,
                               String stepCode,
                               String guardStage,
                               int expectedChapterCount,
                               int expectedSectionCount,
                               Double chapterCoverage,
                               Double sectionCoverage,
                               String decision,
                               String fallbackAction,
                               String errorCode,
                               String errorMessage) {
        logger.info("paper_guard taskId={}, stepCode={}, guardStage={}, expectedChapterCount={}, expectedSectionCount={}, chapterCoverage={}, sectionCoverage={}, decision={}, fallbackAction={}, errorCode={}, errorMessage={}",
                taskId,
                stepCode,
                guardStage,
                expectedChapterCount,
                expectedSectionCount,
                chapterCoverage == null ? "na" : formatCoverage(chapterCoverage),
                sectionCoverage == null ? "na" : formatCoverage(sectionCoverage),
                decision,
                fallbackAction,
                errorCode == null ? "none" : errorCode,
                errorMessage == null ? "" : errorMessage);
    }

    private record StructureExpectation(int expectedChapterCount,
                                        Map<Integer, Integer> expectedSectionCountPerChapter,
                                        int expectedSectionCountTotal) {
    }

    private record StructureCoverage(int generatedChapterCount,
                                     int generatedSectionCount,
                                     int matchedSectionCount,
                                     double chapterCoverage,
                                     double sectionCoverage) {
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
