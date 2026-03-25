package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.ArtifactEntity;
import com.smartark.gateway.db.entity.PaperOutlineVersionEntity;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
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
import com.smartark.gateway.dto.PaperOutlineGenerateRequest;
import com.smartark.gateway.dto.PaperOutlineGenerateResult;
import com.smartark.gateway.dto.PaperManuscriptResult;
import com.smartark.gateway.dto.PaperOutlineResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServicePaperOutlineTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskStepRepository taskStepRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ArtifactRepository artifactRepository;
    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private PaperTopicSessionRepository paperTopicSessionRepository;
    @Mock
    private PaperOutlineVersionRepository paperOutlineVersionRepository;
    @Mock
    private PaperCorpusDocRepository paperCorpusDocRepository;
    @Mock
    private PaperCorpusChunkRepository paperCorpusChunkRepository;
    @Mock
    private TaskExecutorService taskExecutorService;
    @Mock
    private PreviewDeployService previewDeployService;
    @Mock
    private BillingService billingService;
    @Mock
    private StepMemoryService stepMemoryService;

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
    void generatePaperOutline_createsPaperTaskAndSixSteps() {
        RequestContext.setUserId("7");
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(taskStepRepository.save(any(TaskStepEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        PaperOutlineGenerateResult result = taskService.generatePaperOutline(
                new PaperOutlineGenerateRequest("联邦学习隐私保护", "计算机科学", "本科", null)
        );

        assertEquals("queued", result.status());
        assertTrue(result.taskId().length() > 10);

        ArgumentCaptor<TaskEntity> taskCaptor = ArgumentCaptor.forClass(TaskEntity.class);
        verify(taskRepository).save(taskCaptor.capture());
        TaskEntity savedTask = taskCaptor.getValue();
        assertEquals("paper_outline", savedTask.getTaskType());
        assertEquals("paper_outline_7", savedTask.getProjectId());
        assertEquals(7L, savedTask.getUserId());
        assertTrue(savedTask.getInstructions().contains("\"topic\":\"联邦学习隐私保护\""));
        assertTrue(savedTask.getInstructions().contains("\"methodPreference\":\"\""));

        ArgumentCaptor<TaskStepEntity> stepCaptor = ArgumentCaptor.forClass(TaskStepEntity.class);
        verify(taskStepRepository, times(8)).save(stepCaptor.capture());
        List<String> stepCodes = stepCaptor.getAllValues().stream().map(TaskStepEntity::getStepCode).toList();
        assertEquals(List.of("topic_clarify", "academic_retrieve", "rag_index_enrich", "rag_retrieve_rerank", "outline_generate", "outline_expand", "outline_quality_check", "quality_rewrite"), stepCodes);
        verify(taskExecutorService).executeTask(result.taskId());
    }

    @Test
    void getPaperOutline_fallbackToSessionResearchQuestionsWhenOutlineMissing() {
        RequestContext.setUserId("9");
        TaskEntity task = new TaskEntity();
        task.setId("task-1");
        task.setUserId(9L);
        task.setTaskType("paper_outline");

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(99L);
        session.setTaskId("task-1");
        session.setTopic("原始主题");
        session.setTopicRefined("细化主题");
        session.setResearchQuestionsJson("[\"问题A\",\"问题B\"]");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(99L);
        version.setVersionNo(1);
        version.setCitationStyle("GB/T 7714");
        version.setOutlineJson("{\"chapters\":[{\"title\":\"第一章\"}],\"references\":[{\"title\":\"文献1\"}]}");
        version.setManuscriptJson("{\"chapters\":[{\"title\":\"第一章\",\"sections\":[]}]}");
        version.setQualityReportJson(null);
        version.setRewriteRound(0);

        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        when(paperTopicSessionRepository.findByTaskId("task-1")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(99L)).thenReturn(Optional.of(version));

        PaperOutlineResult result = taskService.getPaperOutline("task-1");

        assertEquals("task-1", result.taskId());
        assertEquals("GB/T 7714", result.citationStyle());
        assertEquals(List.of("问题A", "问题B"), result.researchQuestions());
        assertEquals(1, result.chapters().size());
        assertEquals(1, result.references().size());
        assertTrue(result.manuscript().isObject());
        assertEquals(0, result.rewriteRound());
        assertTrue(result.qualityChecks().isObject());
    }

    @Test
    void getPaperOutline_throwsConflictWhenTaskTypeMismatch() {
        RequestContext.setUserId("3");
        TaskEntity task = new TaskEntity();
        task.setId("task-2");
        task.setUserId(3L);
        task.setTaskType("generate");
        when(taskRepository.findById("task-2")).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class, () -> taskService.getPaperOutline("task-2"));
        assertEquals(ErrorCodes.CONFLICT, ex.getCode());
    }

    @Test
    void getPaperManuscript_fallbackToOutlineWhenManuscriptEmpty() {
        RequestContext.setUserId("6");
        TaskEntity task = new TaskEntity();
        task.setId("task-m-1");
        task.setUserId(6L);
        task.setTaskType("paper_outline");

        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setId(66L);
        session.setTaskId("task-m-1");
        session.setTopic("原始主题");
        session.setTopicRefined("细化主题");

        PaperOutlineVersionEntity version = new PaperOutlineVersionEntity();
        version.setSessionId(66L);
        version.setVersionNo(2);
        version.setOutlineJson("{\"chapters\":[{\"title\":\"第一章\"}]}");
        version.setManuscriptJson(null);
        version.setRewriteRound(1);
        version.setQualityScore(java.math.BigDecimal.valueOf(82));

        when(taskRepository.findById("task-m-1")).thenReturn(Optional.of(task));
        when(paperTopicSessionRepository.findByTaskId("task-m-1")).thenReturn(Optional.of(session));
        when(paperOutlineVersionRepository.findTopBySessionIdOrderByVersionNoDesc(66L)).thenReturn(Optional.of(version));

        PaperManuscriptResult result = taskService.getPaperManuscript("task-m-1");
        assertEquals("task-m-1", result.taskId());
        assertEquals("原始主题", result.topic());
        assertEquals("第一章", result.manuscript().path("chapters").path(0).path("title").asText());
        assertEquals(1, result.rewriteRound());
        assertEquals(0, java.math.BigDecimal.valueOf(82).compareTo(result.qualityScore()));
    }

    @Test
    void getPaperManuscript_throwsForbiddenWhenUserMismatch() {
        RequestContext.setUserId("1");
        TaskEntity task = new TaskEntity();
        task.setId("task-m-2");
        task.setUserId(2L);
        task.setTaskType("paper_outline");
        when(taskRepository.findById("task-m-2")).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class, () -> taskService.getPaperManuscript("task-m-2"));
        assertEquals(ErrorCodes.FORBIDDEN, ex.getCode());
    }
}
