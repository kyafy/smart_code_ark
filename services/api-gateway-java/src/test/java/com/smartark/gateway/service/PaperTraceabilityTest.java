package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperCorpusChunkEntity;
import com.smartark.gateway.db.entity.PaperCorpusDocEntity;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.repo.ArtifactRepository;
import com.smartark.gateway.db.repo.PaperCorpusChunkRepository;
import com.smartark.gateway.db.repo.PaperCorpusDocRepository;
import com.smartark.gateway.db.repo.PaperOutlineVersionRepository;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.db.repo.ProjectRepository;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.dto.PaperTraceabilityResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaperTraceabilityTest {

    @Mock private TaskRepository taskRepository;
    @Mock private TaskStepRepository taskStepRepository;
    @Mock private TaskLogRepository taskLogRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ArtifactRepository artifactRepository;
    @Mock private TaskPreviewRepository taskPreviewRepository;
    @Mock private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock private PaperCorpusDocRepository paperCorpusDocRepository;
    @Mock private PaperCorpusChunkRepository paperCorpusChunkRepository;
    @Mock private TaskExecutorService taskExecutorService;
    @Mock private PreviewDeployService previewDeployService;
    @Mock private BillingService billingService;
    @Mock private StepMemoryService stepMemoryService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                taskStepRepository,
                taskLogRepository,
                projectRepository,
                artifactRepository,
                taskPreviewRepository,
                paperTopicSessionRepository,
                paperOutlineVersionRepository,
                paperCorpusDocRepository,
                paperCorpusChunkRepository,
                taskExecutorService,
                previewDeployService,
                billingService,
                stepMemoryService,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getTraceability_returnsChapterEvidenceMapping() {
        RequestContext.setUserId("9");

        TaskEntity task = new TaskEntity();
        task.setId("task-t-1");
        task.setUserId(9L);
        task.setTaskType("paper_outline");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(100L);
        version.setVersionNo(2);
        version.setChapterEvidenceMapJson("""
                [
                  {
                    "citationIndex":1,
                    "chunkUid":"chunk-1",
                    "docUid":"doc-1",
                    "paperId":"crossref:abc",
                    "title":"A",
                    "content":"snippet",
                    "url":"u",
                    "year":2022,
                    "source":"crossref",
                    "vectorScore":0.8,
                    "rerankScore":0.9,
                    "chunkType":"method"
                  }
                ]
                """);
        version.setManuscriptJson("""
                {
                  "chapters":[
                    {
                      "title":"Chapter 1",
                      "sections":[
                        {"title":"S1","content":"text[1]","citations":[1]}
                      ]
                    }
                  ]
                }
                """);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(100L);
        session.setTaskId("task-t-1");

        PaperCorpusDocEntity doc = new PaperCorpusDocEntity();
        doc.setId(200L);

        PaperCorpusChunkEntity chunk = new PaperCorpusChunkEntity();
        chunk.setId(300L);

        when(taskRepository.findById("task-t-1")).thenReturn(Optional.of(task));
        when(paperOutlineVersionRepository.findTopByTaskIdOrderByVersionNoDesc("task-t-1")).thenReturn(Optional.of(version));
        when(paperTopicSessionRepository.findByTaskId("task-t-1")).thenReturn(Optional.of(session));
        when(paperCorpusDocRepository.findBySessionId(100L)).thenReturn(List.of(doc));
        when(paperCorpusChunkRepository.findByDocId(200L)).thenReturn(List.of(chunk));

        PaperTraceabilityResult result = taskService.getPaperTraceability("task-t-1");

        assertEquals("task-t-1", result.taskId());
        assertEquals(1, result.globalEvidenceList().size());
        assertEquals(1, result.chapters().get(0).citationIndices().get(0));
        assertEquals(1, result.totalChunksSearched());
    }

    @Test
    void getTraceability_returnsEmptyWhenNoEvidence() {
        RequestContext.setUserId("9");
        TaskEntity task = new TaskEntity();
        task.setId("task-t-2");
        task.setUserId(9L);
        task.setTaskType("paper_outline");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(101L);
        version.setVersionNo(1);
        version.setChapterEvidenceMapJson(null);
        version.setManuscriptJson("{\"chapters\":[{\"title\":\"C\",\"sections\":[]}]}");

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(101L);
        session.setTaskId("task-t-2");

        when(taskRepository.findById("task-t-2")).thenReturn(Optional.of(task));
        when(paperOutlineVersionRepository.findTopByTaskIdOrderByVersionNoDesc("task-t-2")).thenReturn(Optional.of(version));
        when(paperTopicSessionRepository.findByTaskId("task-t-2")).thenReturn(Optional.of(session));
        when(paperCorpusDocRepository.findBySessionId(101L)).thenReturn(List.of());

        PaperTraceabilityResult result = taskService.getPaperTraceability("task-t-2");
        assertTrue(result.globalEvidenceList().isEmpty());
    }

    @Test
    void getTraceability_throwsOnTaskNotFound() {
        RequestContext.setUserId("9");
        when(taskRepository.findById("missing")).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> taskService.getPaperTraceability("missing"));
        assertEquals(ErrorCodes.NOT_FOUND, ex.getCode());
    }

    @Test
    void getTraceability_citationIndicesMatchGlobalList() {
        RequestContext.setUserId("9");
        TaskEntity task = new TaskEntity();
        task.setId("task-t-3");
        task.setUserId(9L);
        task.setTaskType("paper_outline");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(102L);
        version.setVersionNo(1);
        version.setChapterEvidenceMapJson("""
                [
                  {"citationIndex":1,"chunkUid":"c1","docUid":"d1","paperId":"crossref:x","title":"T","content":"C","url":"U","year":2024,"source":"crossref","vectorScore":0.7,"rerankScore":0.8,"chunkType":"abstract"},
                  {"citationIndex":2,"chunkUid":"c2","docUid":"d2","paperId":"arxiv:x","title":"T2","content":"C2","url":"U2","year":2025,"source":"arxiv","vectorScore":0.6,"rerankScore":0.9,"chunkType":"method"}
                ]
                """);
        version.setManuscriptJson("""
                {
                  "chapters":[
                    {"title":"C1","sections":[{"title":"S","content":"A[1]B[2]","citations":[1,2]}]}
                  ]
                }
                """);

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(102L);
        session.setTaskId("task-t-3");

        when(taskRepository.findById("task-t-3")).thenReturn(Optional.of(task));
        when(paperOutlineVersionRepository.findTopByTaskIdOrderByVersionNoDesc("task-t-3")).thenReturn(Optional.of(version));
        when(paperTopicSessionRepository.findByTaskId("task-t-3")).thenReturn(Optional.of(session));
        when(paperCorpusDocRepository.findBySessionId(102L)).thenReturn(List.of());

        PaperTraceabilityResult result = taskService.getPaperTraceability("task-t-3");
        List<Integer> chapterIndices = result.chapters().get(0).citationIndices();
        List<Integer> globalIndices = result.globalEvidenceList().stream().map(PaperTraceabilityResult.EvidenceItem::citationIndex).toList();
        assertTrue(globalIndices.containsAll(chapterIndices));
    }
}
