package com.smartark.gateway.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.model.PaperSourceItem;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ArxivService;
import com.smartark.gateway.service.CrossrefService;
import com.smartark.gateway.service.QualityGateService;
import com.smartark.gateway.service.RagService;
import com.smartark.gateway.service.SemanticScholarService;
import com.smartark.gateway.service.TemplateRepoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping({"/internal", "/api/internal"})
@Tag(name = "Internal Capability", description = "Internal capability endpoints for DeepAgent runtime callbacks")
public class InternalCapabilityController {
    private final TemplateRepoService templateRepoService;
    private final SemanticScholarService semanticScholarService;
    private final CrossrefService crossrefService;
    private final ArxivService arxivService;
    private final RagService ragService;
    private final QualityGateService qualityGateService;
    private final PaperSourceRepository paperSourceRepository;
    private final PaperTopicSessionRepository paperTopicSessionRepository;
    private final PaperOutlineVersionRepository paperOutlineVersionRepository;
    private final ObjectMapper objectMapper;

    @Value("${smartark.agent.internal-token:smartark-internal}")
    private String internalToken;
    @Value("${smartark.agent.workspace-root:/tmp/smartark/}")
    private String workspaceRoot;

    public InternalCapabilityController(TemplateRepoService templateRepoService,
                                        SemanticScholarService semanticScholarService,
                                        CrossrefService crossrefService,
                                        ArxivService arxivService,
                                        RagService ragService,
                                        QualityGateService qualityGateService,
                                        PaperSourceRepository paperSourceRepository,
                                        PaperTopicSessionRepository paperTopicSessionRepository,
                                        PaperOutlineVersionRepository paperOutlineVersionRepository,
                                        ObjectMapper objectMapper) {
        this.templateRepoService = templateRepoService;
        this.semanticScholarService = semanticScholarService;
        this.crossrefService = crossrefService;
        this.arxivService = arxivService;
        this.ragService = ragService;
        this.qualityGateService = qualityGateService;
        this.paperSourceRepository = paperSourceRepository;
        this.paperTopicSessionRepository = paperTopicSessionRepository;
        this.paperOutlineVersionRepository = paperOutlineVersionRepository;
        this.objectMapper = objectMapper;
    }

    // Endpoints /model/structure and /model/generate-file removed in Phase 3.
    // DeepAgent calls the LLM directly (DEEPAGENT_LLM_DIRECT_ENABLED=true).
    // DTOs ModelStructureRequest and ModelGenerateFileRequest are retained for
    // one additional sprint in case of emergency rollback; they can be deleted after.

    @PostMapping("/template/resolve")
    @Operation(summary = "Resolve template by stack or template id")
    public ResponseEntity<?> resolveTemplate(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) TemplateResolveRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        Map<String, String> stack = request == null ? Map.of() : safeMap(request.stack());
        String templateId = request == null ? "" : defaultText(request.templateId());

        Optional<TemplateRepoService.TemplateSelection> selection = templateRepoService.resolveTemplate(
                templateId,
                stackValue(stack, "backend"),
                stackValue(stack, "frontend"),
                stackValue(stack, "db")
        );
        if (selection.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                    "template_key", "",
                    "template_root", "",
                    "backend_root", "",
                    "frontend_root", "",
                    "metadata", Map.of()
            ));
        }

        TemplateRepoService.TemplateSelection selected = selection.get();
        Map<String, Object> metadata = new LinkedHashMap<>();
        TemplateRepoService.TemplateMetadata rawMetadata = selected.metadata();
        metadata.put("template_key", rawMetadata.templateKey());
        metadata.put("name", rawMetadata.name());
        metadata.put("category", rawMetadata.category());
        metadata.put("description", rawMetadata.description());
        metadata.put("paths", rawMetadata.paths());
        metadata.put("stack", rawMetadata.stack());
        metadata.put("run", rawMetadata.run());
        metadata.put("example_files", rawMetadata.exampleFiles());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("template_key", selected.templateKey());
        result.put("template_root", selected.templateRoot().toAbsolutePath().toString());
        result.put("backend_root", defaultText(selected.backendRoot()));
        result.put("frontend_root", defaultText(selected.frontendRoot()));
        result.put("metadata", metadata);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/academic/search")
    @Operation(summary = "Academic multi-source retrieval")
    public ResponseEntity<?> academicSearch(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) AcademicSearchRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        if (request == null || request.query() == null || request.query().isBlank()) {
            return badRequest("query is required");
        }
        int limit = request.limit() == null ? 20 : Math.max(1, Math.min(request.limit(), 50));
        String discipline = defaultText(request.discipline());
        String query = request.query().trim();

        List<PaperSourceItem> merged = new ArrayList<>();
        merged.addAll(semanticScholarService.searchPapers(query, Math.min(limit, 20)));
        merged.addAll(crossrefService.searchPapers(query, Math.min(limit, 20)));
        if (ArxivService.supportsDiscipline(discipline)) {
            merged.addAll(arxivService.searchPapers(query, Math.min(limit, 20)));
        }

        List<PaperSourceItem> deduped = deduplicatePapers(merged, limit);
        return ResponseEntity.ok(Map.of("results", deduped));
    }

    @PostMapping("/rag/index")
    @Operation(summary = "Index RAG sources into vector store")
    public ResponseEntity<?> ragIndex(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) RagIndexRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        if (request == null || request.sessionId() == null) {
            return badRequest("session_id is required");
        }
        Long sessionId = request.sessionId();
        String discipline = defaultText(request.discipline());
        List<SourcePayload> payloadSources = request.sources() == null ? List.of() : request.sources();

        List<PaperSourceEntity> entities = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (SourcePayload source : payloadSources) {
            if (source == null || isBlank(source.paperId()) || isBlank(source.title())) {
                continue;
            }
            PaperSourceEntity entity = new PaperSourceEntity();
            entity.setSessionId(sessionId);
            entity.setSectionKey(isBlank(source.sectionKey()) ? "global" : source.sectionKey().trim());
            entity.setPaperId(source.paperId().trim());
            entity.setTitle(source.title().trim());
            try {
                entity.setAuthorsJson(objectMapper.writeValueAsString(
                        source.authors() == null ? List.of() : source.authors()));
            } catch (Exception ignored) {
                entity.setAuthorsJson("[]");
            }
            entity.setYear(source.year());
            entity.setVenue(defaultText(source.venue()));
            entity.setUrl(defaultText(source.url()));
            entity.setAbstractText(defaultText(source.abstractText()));
            entity.setEvidenceSnippet(defaultText(source.evidenceSnippet()));
            entity.setRelevanceScore(toBigDecimal(source.relevanceScore()));
            entity.setSource(isBlank(source.source()) ? "semantic_scholar" : source.source().trim());
            entity.setCreatedAt(now);
            entities.add(entity);
        }

        paperSourceRepository.deleteBySessionId(sessionId);
        if (!entities.isEmpty()) {
            paperSourceRepository.saveAll(entities);
        }
        RagService.RagIndexResult indexResult = ragService.indexSources(sessionId, entities, discipline);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunk_count", indexResult.chunkCount());
        result.put("doc_count", indexResult.docCount());
        result.put("source_count", entities.size());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rag/retrieve")
    @Operation(summary = "Retrieve and rerank RAG evidence")
    public ResponseEntity<?> ragRetrieve(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) RagRetrieveRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        if (request == null || request.sessionId() == null || isBlank(request.query())) {
            return badRequest("session_id and query are required");
        }
        int topK = request.topK() == null ? 30 : Math.max(1, Math.min(request.topK(), 100));
        List<RagEvidenceItem> evidence = ragService.retrieveAndRerank(
                request.sessionId(),
                request.query().trim(),
                defaultText(request.discipline()),
                topK
        );
        return ResponseEntity.ok(Map.of("evidence", evidence == null ? List.of() : evidence));
    }

    @PostMapping("/quality/evaluate")
    @Operation(summary = "Evaluate workspace quality gate")
    public ResponseEntity<?> qualityEvaluate(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody(required = false) QualityEvaluateRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        String taskId = request == null ? "" : defaultText(request.taskId());
        String requestedWorkspace = request == null ? "" : defaultText(request.workspaceDir());
        Path workspace = resolveWorkspace(taskId, requestedWorkspace);
        if (workspace == null) {
            return badRequest("task_id or workspace_dir is required");
        }

        QualityGateService.QualityGateResult result = qualityGateService.evaluate(workspace);
        List<String> fixedActions = List.of();
        if (!result.passed()) {
            fixedActions = qualityGateService.autoFix(workspace, result.failedRules());
            if (fixedActions != null && !fixedActions.isEmpty()) {
                result = qualityGateService.evaluate(workspace);
            }
        }
        qualityGateService.persistReport(workspace, result);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("passed", result.passed());
        response.put("score", result.score());
        response.put("failed_rules", result.failedRules());
        response.put("generated_at", result.generatedAt());
        response.put("fixed_actions", fixedActions == null ? List.of() : fixedActions);
        response.put("workspace_dir", workspace.toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/paper/{sessionId}/outline")
    @Operation(summary = "Persist paper outline by session id")
    public ResponseEntity<?> persistOutline(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable("sessionId") Long sessionId,
            @RequestBody(required = false) PaperOutlinePersistRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        PaperTopicSessionEntity session = paperTopicSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return notFound("paper session not found");
        }

        String outlineJson = toJsonString(request == null ? null : request.outlineJson(), "{}");
        String chapterEvidenceMap = toJsonString(request == null ? null : request.chapterEvidenceMap(), "");
        String citationStyle = request == null ? "GB/T 7714" : defaultOr(request.citationStyle(), "GB/T 7714");

        int nextVersionNo = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(sessionId)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(sessionId);
        version.setVersionNo(nextVersionNo);
        version.setCitationStyle(citationStyle);
        version.setOutlineJson(outlineJson);
        version.setChapterEvidenceMapJson(chapterEvidenceMap.isBlank() ? null : chapterEvidenceMap);
        version.setRewriteRound(0);
        version.setCreatedAt(LocalDateTime.now());
        paperOutlineVersionRepository.save(version);

        session.setStatus("outlined");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);

        return ResponseEntity.ok(Map.of(
                "session_id", sessionId,
                "version_no", nextVersionNo
        ));
    }

    @PostMapping("/paper/{sessionId}/manuscript")
    @Operation(summary = "Persist paper manuscript/quality by session id")
    public ResponseEntity<?> persistManuscript(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @PathVariable("sessionId") Long sessionId,
            @RequestBody(required = false) PaperManuscriptPersistRequest request) {
        if (!isAuthorized(token)) {
            return unauthorized();
        }
        PaperTopicSessionEntity session = paperTopicSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return notFound("paper session not found");
        }

        PaperOutlineVersionEntity version = paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(sessionId)
                .orElseGet(() -> {
                    PaperOutlineVersionEntity created = new PaperOutlineVersionEntity();
                    created.setSessionId(sessionId);
                    created.setVersionNo(1);
                    created.setCitationStyle("GB/T 7714");
                    created.setOutlineJson("{}");
                    created.setRewriteRound(0);
                    created.setCreatedAt(LocalDateTime.now());
                    return created;
                });

        String manuscriptJson = toJsonString(request == null ? null : request.manuscriptJson(), "{}");
        String qualityReportJson = toJsonString(request == null ? null : request.qualityReport(), null);
        version.setManuscriptJson(manuscriptJson);
        if (qualityReportJson != null) {
            version.setQualityReportJson(qualityReportJson);
        }
        if (request != null && request.qualityScore() != null) {
            version.setQualityScore(BigDecimal.valueOf(request.qualityScore()));
        }
        if (version.getCreatedAt() == null) {
            version.setCreatedAt(LocalDateTime.now());
        }
        if (version.getRewriteRound() == null) {
            version.setRewriteRound(0);
        }
        PaperOutlineVersionEntity saved = paperOutlineVersionRepository.save(version);

        session.setStatus(request != null && request.qualityReport() != null ? "checked" : "expanded");
        session.setUpdatedAt(LocalDateTime.now());
        paperTopicSessionRepository.save(session);

        return ResponseEntity.ok(Map.of(
                "session_id", sessionId,
                "version_no", saved.getVersionNo()
        ));
    }

    private boolean isAuthorized(String token) {
        return internalToken != null && internalToken.equals(token);
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid internal token"));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", message));
    }

    private ResponseEntity<Map<String, Object>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", message));
    }

    private Path resolveWorkspace(String taskId, String workspaceDir) {
        if (!isBlank(workspaceDir)) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        if (!isBlank(taskId)) {
            return Paths.get(workspaceRoot, taskId).toAbsolutePath().normalize();
        }
        return null;
    }

    private List<PaperSourceItem> deduplicatePapers(List<PaperSourceItem> source, int limit) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<PaperSourceItem> sorted = source.stream()
                .filter(item -> item != null && !isBlank(item.getTitle()))
                .sorted(Comparator.comparing(PaperSourceItem::getRelevanceScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Map<String, PaperSourceItem> deduped = new LinkedHashMap<>();
        for (PaperSourceItem item : sorted) {
            String key = normalizeTitle(item.getTitle());
            if (key.isBlank()) {
                continue;
            }
            deduped.putIfAbsent(key, item);
            if (deduped.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]", "");
    }

    private BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private String toJsonString(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return fallback;
        }
    }

    private Map<String, String> safeMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            copy.put(entry.getKey(), defaultText(entry.getValue()));
        }
        return copy;
    }

    private String stackValue(Map<String, String> stack, String key) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return defaultText(stack.get(key));
    }

    private String defaultText(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ModelStructureRequest(
            String prd,
            Map<String, String> stack,
            String instructions
    ) {
    }

    public record ModelGenerateFileRequest(
            @JsonProperty("file_path") String filePath,
            String prd,
            @JsonProperty("tech_stack") String techStack,
            @JsonProperty("project_structure") String projectStructure,
            String group,
            String instructions
    ) {
    }

    public record TemplateResolveRequest(
            Map<String, String> stack,
            @JsonProperty("template_id") String templateId
    ) {
    }

    public record AcademicSearchRequest(
            String query,
            String discipline,
            Integer limit
    ) {
    }

    public record RagIndexRequest(
            @JsonProperty("session_id") Long sessionId,
            List<SourcePayload> sources,
            String discipline
    ) {
    }

    public record SourcePayload(
            @JsonAlias({"paperId", "paper_id"}) String paperId,
            @JsonAlias({"title"}) String title,
            @JsonAlias({"authors"}) List<String> authors,
            @JsonAlias({"year"}) Integer year,
            @JsonAlias({"venue"}) String venue,
            @JsonAlias({"url"}) String url,
            @JsonAlias({"abstractText", "abstract_text"}) String abstractText,
            @JsonAlias({"evidenceSnippet", "evidence_snippet"}) String evidenceSnippet,
            @JsonAlias({"relevanceScore", "relevance_score"}) Double relevanceScore,
            @JsonAlias({"source"}) String source,
            @JsonAlias({"sectionKey", "section_key"}) String sectionKey
    ) {
    }

    public record RagRetrieveRequest(
            @JsonProperty("session_id") Long sessionId,
            String query,
            String discipline,
            @JsonProperty("top_k") Integer topK
    ) {
    }

    public record QualityEvaluateRequest(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("workspace_dir") String workspaceDir
    ) {
    }

    public record PaperOutlinePersistRequest(
            @JsonProperty("outline_json") JsonNode outlineJson,
            @JsonProperty("citation_style") String citationStyle,
            @JsonProperty("chapter_evidence_map") JsonNode chapterEvidenceMap
    ) {
    }

    public record PaperManuscriptPersistRequest(
            @JsonProperty("manuscript_json") JsonNode manuscriptJson,
            @JsonProperty("quality_report") JsonNode qualityReport,
            @JsonProperty("quality_score") Double qualityScore
    ) {
    }
}
