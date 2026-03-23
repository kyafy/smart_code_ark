package com.smartark.gateway.agent.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.agent.model.RagEvidenceItem;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutlineExpandStepCitationTest {

    @Mock private ModelService modelService;
    @Mock private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock private PaperSourceRepository paperSourceRepository;

    @Test
    void buildFullCitationMap_joinsWithRagEvidence() throws Exception {
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );

        AgentExecutionContext context = baseContext();
        context.setRagEvidenceItems(List.of(rag("chunk-1", "doc-1", "crossref:1")));

        when(modelService.expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ObjectMapper().readTree("""
                        {
                          "chapters":[{"title":"C1","sections":[{"title":"S1","content":"Text[1]","coreArgument":"A","citations":[1]}]}],
                          "citationMap":[{"citationIndex":1,"chunkUid":"chunk-1","title":"T","relevance":0.8}]
                        }
                        """));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        String savedMap = cap.getValue().getChapterEvidenceMapJson();
        assertTrue(savedMap.contains("\"chunkUid\":\"chunk-1\""));
        assertTrue(savedMap.contains("\"docUid\":\"doc-1\""));
    }

    @Test
    void buildFullCitationMap_skipsUnmatchedChunks() throws Exception {
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );

        AgentExecutionContext context = baseContext();
        context.setRagEvidenceItems(List.of(rag("chunk-1", "doc-1", "crossref:1")));

        when(modelService.expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ObjectMapper().readTree("""
                        {
                          "chapters":[{"title":"C1","sections":[{"title":"S1","content":"Text","coreArgument":"A","citations":[]}]}],
                          "citationMap":[{"citationIndex":1,"chunkUid":"missing-chunk","title":"T","relevance":0.8}]
                        }
                        """));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        String savedMap = cap.getValue().getChapterEvidenceMapJson();
        if (savedMap != null) {
            assertTrue(!savedMap.contains("missing-chunk"));
        }
    }

    @Test
    void buildFullCitationMap_preservesCitationIndex() throws Exception {
        OutlineExpandStep step = new OutlineExpandStep(
                modelService,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperSourceRepository,
                new ObjectMapper()
        );

        AgentExecutionContext context = baseContext();
        context.setRagEvidenceItems(List.of(rag("chunk-9", "doc-9", "arxiv:9")));

        when(modelService.expandPaperOutline(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new ObjectMapper().readTree("""
                        {
                          "chapters":[{"title":"C1","sections":[{"title":"S1","content":"Text[7]","coreArgument":"A","citations":[7]}]}],
                          "citationMap":[{"citationIndex":7,"chunkUid":"chunk-9","title":"T","relevance":0.8}]
                        }
                        """));

        step.execute(context);

        ArgumentCaptor<PaperOutlineVersionEntity> cap = ArgumentCaptor.forClass(PaperOutlineVersionEntity.class);
        verify(paperOutlineVersionRepository).save(cap.capture());
        String savedMap = cap.getValue().getChapterEvidenceMapJson();
        assertTrue(savedMap.contains("\"citationIndex\":7"));
    }

    private AgentExecutionContext baseContext() {
        AgentExecutionContext context = new AgentExecutionContext();
        TaskEntity task = new TaskEntity();
        task.setId("task-expand-cite");
        task.setProjectId("project-expand-cite");
        context.setTask(task);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(10L);
        session.setTaskId("task-expand-cite");
        session.setTopic("topic");
        session.setTopicRefined("topic refined");
        session.setDiscipline("cs");
        session.setDegreeLevel("undergraduate");
        session.setResearchQuestionsJson("[]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(10L);
        version.setVersionNo(1);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"C\",\"sections\":[{\"title\":\"S\"}]}]}");

        when(paperTopicSessionRepository.findByTaskId("task-expand-cite")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(10L)).thenReturn(Optional.of(version));
        when(paperSourceRepository.findBySessionIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(new PaperSourceEntity()));

        return context;
    }

    private RagEvidenceItem rag(String chunkUid, String docUid, String paperId) {
        RagEvidenceItem item = new RagEvidenceItem();
        item.setChunkUid(chunkUid);
        item.setDocUid(docUid);
        item.setPaperId(paperId);
        item.setTitle("title");
        item.setContent("content");
        item.setUrl("url");
        item.setYear(2024);
        item.setVectorScore(0.7);
        item.setRerankScore(0.9);
        item.setChunkType("abstract");
        return item;
    }
}
