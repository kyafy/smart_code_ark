package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.RagEvidenceItem;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperSourceEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperSourceRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.service.ModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineExpandStepBatchTest {
    @Mock
    private ModelService modelService;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private PaperSourceRepository paperSourceRepository;

    private final ObjectMapper om = new ObjectMapper();

    // ───────── helper builders ─────────

    private OutlineExpandStep step(boolean batchEnabled, int batchChapterSize, int batchMaxRetries, int evidenceTopK) {
        return new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                om,
                batchEnabled,
                batchChapterSize,
                batchMaxRetries,
                evidenceTopK
        );
    }

    private AgentExecutionContext baseContext(String outlineJson) {
        return baseContext(outlineJson, null);
    }

    private AgentExecutionContext baseContext(String outlineJson, List<RagEvidenceItem> ragItems) {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-batch-test");
        task.setProjectId("project-batch-test");
        context.setTask(task);
        if (ragItems != null) {
            context.setRagEvidenceItems(ragItems);
        }

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(1L);
        session.setTaskId("task-batch-test");
        session.setTopic("测试主题");
        session.setTopicRefined("细化测试主题");
        session.setDiscipline("cs");
        session.setDegreeLevel("master");
        session.setResearchQuestionsJson("[\"Q1\",\"Q2\"]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(1L);
        version.setVersionNo(1);
        version.setOutlineJson(outlineJson);

        when(paperTopicSessionRepository.findByTaskId("task-batch-test")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(1L)).thenReturn(Optional.of(version));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(1L)).thenReturn(List.of(new PaperSourceEntity()));

        return context;
    }

    private RagEvidenceItem rag(String chunkUid, String paperId, String title, double rerankScore) {
        RagEvidenceItem item = new RagEvidenceItem();
        item.setChunkUid(chunkUid);
        item.setDocUid("doc-" + chunkUid);
        item.setPaperId(paperId);
        item.setTitle(title);
        item.setContent("content about " + title);
        item.setUrl("https://example.com/" + chunkUid);
        item.setYear(2024);
        item.setVectorScore(0.7);
        item.setRerankScore(rerankScore);
        item.setChunkType("abstract");
        return item;
    }

    private String validBatchResponse(String chapterTitle, String sectionTitle, String content, String citationMap) {
        return """
                {
                  "chapters":[{
                    "index":1,"title":"%s","summary":"S","objective":"O",
                    "sections":[{"title":"%s","content":"%s","coreArgument":"论点","method":"","dataPlan":"","expectedResult":"","citations":[%s]}]
                  }],
                  "citationMap":[%s]
                }
                """.formatted(chapterTitle, sectionTitle, content, citationMap.isEmpty() ? "" : "1", citationMap);
    }

    // ───────── 1. Basic batch expand (2 chapters in 1 batch) ─────────

    @Test
    void execute_shouldUseBatchExpandWhenEnabled() throws Exception {
        OutlineExpandStep s = step(true, 2, 1, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]},
                  {"title":"C2","summary":"S2","sections":[{"title":"2.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        when(modelService.expandPaperOutlineBatch(
                eq("task-batch-test"), eq("project-batch-test"), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-2"), eq(2)
        )).thenReturn(om.readTree("""
                {
                  "chapters":[
                    {"index":1,"title":"C1","summary":"S1","objective":"O1","sections":[{"title":"1.1","content":"正文内容一","coreArgument":"论点1","method":"","dataPlan":"","expectedResult":"","citations":[]}]},
                    {"index":2,"title":"C2","summary":"S2","objective":"O2","sections":[{"title":"2.1","content":"正文内容二","coreArgument":"论点2","method":"","dataPlan":"","expectedResult":"","citations":[]}]}
                  ],
                  "citationMap":[]
                }
                """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        String manuscript = cap.getValue().getManuscriptJson();
        assertThat(manuscript).contains("正文内容一").contains("正文内容二");
    }

    // ───────── 2. Multi-batch: 3 chapters with batchChapterSize=1 → 3 batches ─────────

    @Test
    void execute_shouldSplitIntoMultipleBatches() throws Exception {
        OutlineExpandStep s = step(true, 1, 0, 8);
        String outline = """
                {"chapters":[
                  {"title":"绪论","summary":"S1","sections":[{"title":"1.1 研究背景"}]},
                  {"title":"文献综述","summary":"S2","sections":[{"title":"2.1 相关工作"}]},
                  {"title":"研究方法","summary":"S3","sections":[{"title":"3.1 实验设计"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // Batch 1: chapter 1
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-1"), eq(3)
        )).thenReturn(om.readTree(validBatchResponse("绪论", "1.1 研究背景", "绪论正文详细内容", "")));

        // Batch 2: chapter 2
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("2-2"), eq(3)
        )).thenReturn(om.readTree(validBatchResponse("文献综述", "2.1 相关工作", "文献综述正文详细内容", "")));

        // Batch 3: chapter 3
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("3-3"), eq(3)
        )).thenReturn(om.readTree(validBatchResponse("研究方法", "3.1 实验设计", "研究方法正文详细内容", "")));

        s.execute(ctx);

        verify(modelService, times(3)).expandPaperOutlineBatch(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(3));

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        JsonNode saved = om.readTree(cap.getValue().getManuscriptJson());
        assertThat(saved.path("chapters").size()).isEqualTo(3);
        assertThat(saved.path("chapters").get(0).path("title").asText()).isEqualTo("绪论");
        assertThat(saved.path("chapters").get(2).path("title").asText()).isEqualTo("研究方法");
    }

    // ───────── 3. Citation merge: deduplication across batches ─────────

    @Test
    void execute_shouldDeduplicateCitationsAcrossBatches() throws Exception {
        OutlineExpandStep s = step(true, 1, 0, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]},
                  {"title":"C2","summary":"S2","sections":[{"title":"2.1"}]}
                ]}""";
        List<RagEvidenceItem> ragItems = List.of(
                rag("chunk-A", "crossref:A", "Paper A", 0.9),
                rag("chunk-B", "arxiv:B", "Paper B", 0.8)
        );
        AgentExecutionContext ctx = baseContext(outline, ragItems);

        // Batch 1 cites chunk-A (local index 1) and chunk-B (local index 2)
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-1"), eq(2)
        )).thenReturn(om.readTree("""
                {
                  "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                    {"title":"1.1","content":"引用A和B的正文","coreArgument":"论点","citations":[1,2]}
                  ]}],
                  "citationMap":[
                    {"citationIndex":1,"chunkUid":"chunk-A","title":"Paper A"},
                    {"citationIndex":2,"chunkUid":"chunk-B","title":"Paper B"}
                  ]
                }
                """));

        // Batch 2 also cites chunk-A (local index 1) → should reuse global index
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("2-2"), eq(2)
        )).thenReturn(om.readTree("""
                {
                  "chapters":[{"index":2,"title":"C2","summary":"S2","objective":"O","sections":[
                    {"title":"2.1","content":"再次引用A的正文","coreArgument":"论点","citations":[1]}
                  ]}],
                  "citationMap":[
                    {"citationIndex":1,"chunkUid":"chunk-A","title":"Paper A"}
                  ]
                }
                """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        String citationMapJson = cap.getValue().getChapterEvidenceMapJson();
        assertThat(citationMapJson).isNotNull().isNotBlank();

        JsonNode citationMap = om.readTree(citationMapJson);
        // Should have exactly 2 unique citations (chunk-A and chunk-B), not 3
        assertThat(citationMap.size()).isEqualTo(2);

        // Verify chapter 2 section citations are remapped to global index of chunk-A (which is 1)
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        JsonNode ch2Citations = manuscript.path("chapters").get(1).path("sections").get(0).path("citations");
        assertThat(ch2Citations.get(0).asInt()).isEqualTo(1); // chunk-A's global index
    }

    // ───────── 4. Quality gate: placeholder text should be rejected ─────────

    @Test
    void execute_shouldRejectPlaceholderContent() throws Exception {
        OutlineExpandStep s = step(true, 2, 0, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // All content is placeholder → quality gate should fail
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                            {"title":"1.1","content":"该节暂无正文","coreArgument":"待补充","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        assertThatThrownBy(() -> s.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("质量门禁未通过");
    }

    // ───────── 5. Quality gate: no sections should be rejected ─────────

    @Test
    void execute_shouldRejectChapterWithNoSections() throws Exception {
        OutlineExpandStep s = step(true, 2, 0, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // Return chapter with empty sections array → schema invalid, retries exhausted
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"","objective":"O","sections":[]}],
                          "citationMap":[]
                        }
                        """));

        assertThatThrownBy(() -> s.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("批次扩写失败");
    }

    // ───────── 6. Quality gate retry: first attempt fails, second passes ─────────

    @Test
    void execute_shouldRetryOnQualityGateFailure() throws Exception {
        OutlineExpandStep s = step(true, 2, 1, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // First call returns placeholder → quality gate fail
        // Second call returns valid content → pass
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                            {"title":"1.1","content":"placeholder text here","coreArgument":"placeholder","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                            {"title":"1.1","content":"经过深入分析发现该方法有效提升了系统性能","coreArgument":"该方法显著提升性能","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        verify(modelService, times(2)).expandPaperOutlineBatch(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        assertThat(cap.getValue().getManuscriptJson()).contains("经过深入分析");
    }

    // ───────── 7. Schema invalid → retry then fail ─────────

    @Test
    void execute_shouldRetryOnSchemaInvalid() throws Exception {
        OutlineExpandStep s = step(true, 2, 1, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // First: chapters array empty (schema invalid)
        // Second: valid
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {"chapters":[]}
                        """))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                            {"title":"1.1","content":"详细的研究方法描述内容","coreArgument":"核心论点","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        verify(modelService, times(2)).expandPaperOutlineBatch(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    // ───────── 8. Evidence selection: ragItems filtered per batch ─────────

    @Test
    void execute_shouldSelectRelevantEvidencePerBatch() throws Exception {
        OutlineExpandStep s = step(true, 1, 0, 2);
        String outline = """
                {"chapters":[
                  {"title":"深度学习","summary":"DL","sections":[{"title":"CNN架构"}]},
                  {"title":"数据库优化","summary":"DB","sections":[{"title":"索引策略"}]}
                ]}""";
        List<RagEvidenceItem> ragItems = List.of(
                rag("chunk-dl1", "arxiv:dl1", "深度学习框架对比", 0.9),
                rag("chunk-dl2", "arxiv:dl2", "CNN卷积网络分析", 0.85),
                rag("chunk-db1", "crossref:db1", "数据库索引优化策略", 0.88),
                rag("chunk-db2", "crossref:db2", "SQL查询优化", 0.7)
        );
        AgentExecutionContext ctx = baseContext(outline, ragItems);

        // Batch 1 (深度学习)
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-1"), eq(2)
        )).thenReturn(om.readTree(validBatchResponse("深度学习", "CNN架构", "深度学习方法分析的完整正文", "")));

        // Batch 2 (数据库优化)
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("2-2"), eq(2)
        )).thenReturn(om.readTree(validBatchResponse("数据库优化", "索引策略", "数据库索引优化的完整正文", "")));

        s.execute(ctx);

        // Both batches should succeed (evidence was selected for each)
        verify(modelService, times(2)).expandPaperOutlineBatch(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(2));
    }

    // ───────── 9. batchEnabled=false → single expand path ─────────

    @Test
    void execute_shouldUseSingleExpandWhenBatchDisabled() throws Exception {
        OutlineExpandStep s = step(false, 2, 2, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]}
                ]}""";
        AgentExecutionContext ctx = baseContext(outline);

        when(modelService.expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"title":"C1","sections":[{"title":"1.1","content":"单次扩展完整正文","coreArgument":"论点","citations":[]}]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        // Should call single expand, NOT batch
        verify(modelService, times(1)).expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(modelService, times(0)).expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        assertThat(cap.getValue().getManuscriptJson()).contains("单次扩展完整正文");
    }

    // ───────── 10. Citation index remapping: batch-local to global ─────────

    @Test
    void execute_shouldRemapCitationIndicesFromLocalToGlobal() throws Exception {
        OutlineExpandStep s = step(true, 1, 0, 8);
        String outline = """
                {"chapters":[
                  {"title":"C1","summary":"S1","sections":[{"title":"1.1"}]},
                  {"title":"C2","summary":"S2","sections":[{"title":"2.1"}]}
                ]}""";
        List<RagEvidenceItem> ragItems = List.of(
                rag("chunk-X", "crossref:X", "Paper X", 0.9),
                rag("chunk-Y", "arxiv:Y", "Paper Y", 0.8),
                rag("chunk-Z", "arxiv:Z", "Paper Z", 0.7)
        );
        AgentExecutionContext ctx = baseContext(outline, ragItems);

        // Batch 1: uses local citation index 1 → chunk-Y
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("1-1"), eq(2)
        )).thenReturn(om.readTree("""
                {
                  "chapters":[{"index":1,"title":"C1","summary":"S1","objective":"O","sections":[
                    {"title":"1.1","content":"引用Y的正文内容","coreArgument":"论点","citations":[1]}
                  ]}],
                  "citationMap":[{"citationIndex":1,"chunkUid":"chunk-Y","title":"Paper Y"}]
                }
                """));

        // Batch 2: uses local citation index 1 → chunk-Z, index 2 → chunk-Y (same as batch 1)
        when(modelService.expandPaperOutlineBatch(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("2-2"), eq(2)
        )).thenReturn(om.readTree("""
                {
                  "chapters":[{"index":2,"title":"C2","summary":"S2","objective":"O","sections":[
                    {"title":"2.1","content":"引用Z和Y的正文内容","coreArgument":"论点","citations":[1,2]}
                  ]}],
                  "citationMap":[
                    {"citationIndex":1,"chunkUid":"chunk-Z","title":"Paper Z"},
                    {"citationIndex":2,"chunkUid":"chunk-Y","title":"Paper Y"}
                  ]
                }
                """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());

        // Global citation map: chunk-Y → 1, chunk-Z → 2
        JsonNode citationMap = om.readTree(cap.getValue().getChapterEvidenceMapJson());
        assertThat(citationMap.size()).isEqualTo(2);

        // Check manuscript: chapter 1 cites [1] (chunk-Y→global 1)
        // chapter 2 cites [2, 1] (chunk-Z→global 2, chunk-Y→global 1)
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        JsonNode ch1Cites = manuscript.path("chapters").get(0).path("sections").get(0).path("citations");
        assertThat(ch1Cites.get(0).asInt()).isEqualTo(1); // chunk-Y global index

        JsonNode ch2Cites = manuscript.path("chapters").get(1).path("sections").get(0).path("citations");
        // chunk-Z → global 2, chunk-Y → global 1 (reuse)
        assertThat(ch2Cites.size()).isEqualTo(2);
        assertThat(ch2Cites.get(0).asInt()).isEqualTo(2); // chunk-Z
        assertThat(ch2Cites.get(1).asInt()).isEqualTo(1); // chunk-Y (dedup)
    }

    // ───────── 11. Flatten: 3-level outline → model receives 2-level sections ─────────

    @Test
    void execute_shouldFlattenSubsectionsIntoSections() throws Exception {
        OutlineExpandStep s = step(true, 2, 0, 8);
        // 三级大纲：1 chapter, 1 section with 3 subsections → 扁平化后应有 3 sections
        String threeLevel = """
                {"chapters":[{
                  "title":"绪论","summary":"绪论摘要",
                  "sections":[{
                    "title":"研究背景",
                    "subsections":[
                      {"subsection":"问题提出","evidence":[]},
                      {"subsection":"研究意义","evidence":[]},
                      {"subsection":"研究范围","evidence":[]}
                    ]
                  }]
                }]}""";
        AgentExecutionContext ctx = baseContext(threeLevel);

        // Model must return 3 sections (matching the flattened outline)
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"绪论","summary":"绪论摘要","objective":"O","sections":[
                            {"title":"研究背景 — 问题提出","content":"问题提出的完整正文","coreArgument":"核心论点1","citations":[]},
                            {"title":"研究背景 — 研究意义","content":"研究意义的完整正文","coreArgument":"核心论点2","citations":[]},
                            {"title":"研究背景 — 研究范围","content":"研究范围的完整正文","coreArgument":"核心论点3","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        // Verify all 3 sections are present in the manuscript
        assertThat(manuscript.path("chapters").get(0).path("sections").size()).isEqualTo(3);
        assertThat(manuscript.path("chapters").get(0).path("sections").get(0).path("content").asText())
                .contains("问题提出");
        assertThat(manuscript.path("chapters").get(0).path("sections").get(2).path("content").asText())
                .contains("研究范围");
    }

    // ───────── 12. Flatten: mixed levels (some sections with subsections, some without) ─────────

    @Test
    void execute_shouldHandleMixedSectionsWithAndWithoutSubsections() throws Exception {
        OutlineExpandStep s = step(true, 2, 0, 8);
        // Chapter with 2 sections: first has 2 subsections, second has none → total 3 flat sections
        String mixedOutline = """
                {"chapters":[{
                  "title":"方法论","summary":"方法",
                  "sections":[
                    {"title":"实验设计","subsections":[
                      {"subsection":"变量控制"},
                      {"subsection":"样本选择"}
                    ]},
                    {"title":"数据分析方法"}
                  ]
                }]}""";
        AgentExecutionContext ctx = baseContext(mixedOutline);

        // Must return 3 sections (2 from flattened subsections + 1 direct)
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"方法论","summary":"方法","objective":"O","sections":[
                            {"title":"实验设计 — 变量控制","content":"变量控制方法的完整正文","coreArgument":"论点A","citations":[]},
                            {"title":"实验设计 — 样本选择","content":"样本选择策略的完整正文","coreArgument":"论点B","citations":[]},
                            {"title":"数据分析方法","content":"数据分析方法的完整正文","coreArgument":"论点C","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        assertThat(manuscript.path("chapters").get(0).path("sections").size()).isEqualTo(3);
    }

    // ───────── 13. Section count mismatch → schema invalid, triggers retry ─────────

    @Test
    void execute_shouldRejectWhenModelReturnsTooFewSections() throws Exception {
        OutlineExpandStep s = step(true, 2, 1, 8);
        // Outline expects 3 sections after flattening
        String outline = """
                {"chapters":[{
                  "title":"C1","summary":"S",
                  "sections":[{
                    "title":"研究背景",
                    "subsections":[
                      {"subsection":"子节1"},
                      {"subsection":"子节2"},
                      {"subsection":"子节3"}
                    ]
                  }]
                }]}""";
        AgentExecutionContext ctx = baseContext(outline);

        // First attempt: model only returns 1 section (mismatch → schema invalid)
        // Second attempt: model returns correct 3 sections
        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S","objective":"O","sections":[
                            {"title":"研究背景","content":"只有一段正文","coreArgument":"论点","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"C1","summary":"S","objective":"O","sections":[
                            {"title":"研究背景 — 子节1","content":"子节1完整正文","coreArgument":"论点1","citations":[]},
                            {"title":"研究背景 — 子节2","content":"子节2完整正文","coreArgument":"论点2","citations":[]},
                            {"title":"研究背景 — 子节3","content":"子节3完整正文","coreArgument":"论点3","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        // Should have called model twice (first attempt rejected due to section count)
        verify(modelService, times(2)).expandPaperOutlineBatch(
                anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt());

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        assertThat(manuscript.path("chapters").get(0).path("sections").size()).isEqualTo(3);
    }

    // ───────── 14. Already flat outline → no change after flatten ─────────

    @Test
    void execute_shouldPassThroughAlreadyFlatOutline() throws Exception {
        OutlineExpandStep s = step(true, 2, 0, 8);
        // 已经是二级结构的大纲（没有 subsections）
        String flatOutline = """
                {"chapters":[{
                  "title":"结论","summary":"S",
                  "sections":[
                    {"title":"研究总结"},
                    {"title":"未来展望"}
                  ]
                }]}""";
        AgentExecutionContext ctx = baseContext(flatOutline);

        when(modelService.expandPaperOutlineBatch(anyString(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(om.readTree("""
                        {
                          "chapters":[{"index":1,"title":"结论","summary":"S","objective":"O","sections":[
                            {"title":"研究总结","content":"研究总结完整正文","coreArgument":"总结论点","citations":[]},
                            {"title":"未来展望","content":"未来展望完整正文","coreArgument":"展望论点","citations":[]}
                          ]}],
                          "citationMap":[]
                        }
                        """));

        s.execute(ctx);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        JsonNode manuscript = om.readTree(cap.getValue().getManuscriptJson());
        assertThat(manuscript.path("chapters").get(0).path("sections").size()).isEqualTo(2);
    }
}
