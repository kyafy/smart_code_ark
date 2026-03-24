package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
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
import com.smartark.gateway.dto.ContractReportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceDeliveryReportTest {

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

    @TempDir
    Path tempDir;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository, taskStepRepository, taskLogRepository,
                projectRepository, artifactRepository, taskPreviewRepository,
                paperTopicSessionRepository, paperOutlineVersionRepository,
                paperCorpusDocRepository, paperCorpusChunkRepository,
                taskExecutorService, previewDeployService, billingService,
                stepMemoryService, new ObjectMapper()
        );
        ReflectionTestUtils.setField(taskService, "workspaceRoot", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getContractReport_shouldThrowNotFoundWhenReportMissing() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("task-a", 1L, "finished");
        when(taskRepository.findById("task-a")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getContractReport("task-a"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCodes.DELIVERY_REPORT_NOT_FOUND);
                    assertThat(be.getMessage()).isEqualTo("contract_report.json not found");
                });
    }

    @Test
    void getContractReport_shouldThrowForbiddenWhenUserMismatch() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("task-b", 2L, "finished");
        when(taskRepository.findById("task-b")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getContractReport("task-b"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCodes.FORBIDDEN);
                    assertThat(be.getMessage()).isEqualTo("forbidden");
                });
    }

    @Test
    void validateDelivery_shouldThrowConflictWhenTaskNotFinished() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("task-c", 1L, "running");
        when(taskRepository.findById("task-c")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.validateDelivery("task-c", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(ErrorCodes.DELIVERY_VALIDATE_STATE_INVALID);
                    assertThat(be.getMessage()).contains("finished");
                });
    }

    @Test
    void validateDelivery_shouldTreatNullAutoFixAsFalse() {
        RequestContext.setUserId("1");
        TaskEntity task = buildTask("task-d", 1L, "finished");
        when(taskRepository.findById("task-d")).thenReturn(Optional.of(task));
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContractReportResult result = taskService.validateDelivery("task-d", null);

        assertThat(result).isNotNull();
        assertThat(result.fixedActions()).isEmpty();
        assertThat(result.failedRules()).contains("missing_required_file");

        ArgumentCaptor<TaskLogEntity> logCaptor = ArgumentCaptor.forClass(TaskLogEntity.class);
        verify(taskLogRepository, atLeast(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues().stream().map(TaskLogEntity::getContent))
                .anyMatch(content -> content != null && content.contains("autoFix=false"));
    }

    private TaskEntity buildTask(String taskId, Long userId, String status) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setUserId(userId);
        task.setStatus(status);
        task.setTaskType("generate");
        return task;
    }
}
