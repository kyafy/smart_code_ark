package com.smartark.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskEntity;
import com.smartark.gateway.db.entity.TaskLogEntity;
import com.smartark.gateway.db.entity.TaskStepEntity;
import com.smartark.gateway.db.repo.TaskLogRepository;
import com.smartark.gateway.db.repo.TaskRepository;
import com.smartark.gateway.db.repo.TaskStepRepository;
import com.smartark.gateway.dto.InternalTaskLogRequest;
import com.smartark.gateway.dto.InternalTaskStepUpdateRequest;
import com.smartark.gateway.service.TaskExecutionModeResolver;
import com.smartark.gateway.service.TaskExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalAgentControllerTest {

    @Mock
    private TaskRepository taskRepository;
    @Mock
    private TaskStepRepository taskStepRepository;
    @Mock
    private TaskLogRepository taskLogRepository;
    @Mock
    private TaskExecutorService taskExecutorService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        InternalAgentController controller = new InternalAgentController(
                taskRepository,
                taskStepRepository,
                taskLogRepository,
                taskExecutorService
        );
        ReflectionTestUtils.setField(controller, "internalToken", "test-token");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void dispatch_shouldInvokeExecutorAndReturnDecision() throws Exception {
        TaskExecutionModeResolver.TaskExecutionDecision decision =
                new TaskExecutionModeResolver.TaskExecutionDecision(
                        "t1", "generate", "ab", "deepagent", "ab_hit", 30, 50, true
                );
        when(taskExecutorService.getExecutionDecision("t1")).thenReturn(decision);

        mockMvc.perform(post("/api/internal/task/t1/dispatch")
                        .header("X-Internal-Token", "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("t1"))
                .andExpect(jsonPath("$.data.selectedMode").value("deepagent"));

        verify(taskExecutorService).executeTask("t1");
    }

    @Test
    void executionDecision_shouldRequireValidToken() throws Exception {
        mockMvc.perform(get("/api/internal/task/t1/execution-decision")
                        .header("X-Internal-Token", "bad-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void appendLog_shouldPersistTaskLog() throws Exception {
        TaskEntity task = buildTask("t2");
        when(taskRepository.findById("t2")).thenReturn(Optional.of(task));
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(i -> i.getArgument(0));

        InternalTaskLogRequest request = new InternalTaskLogRequest("info", "build fix round 1");
        mockMvc.perform(post("/api/internal/task/t2/log")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<TaskLogEntity> captor = ArgumentCaptor.forClass(TaskLogEntity.class);
        verify(taskLogRepository).save(captor.capture());
        assertThat(captor.getValue().getContent()).contains("deepagent");
    }

    @Test
    void updateTaskStep_shouldCreateMissingStepAndSetTaskRunning() throws Exception {
        TaskEntity task = buildTask("t3");
        task.setStatus("queued");
        TaskStepEntity existing = new TaskStepEntity();
        existing.setTaskId("t3");
        existing.setStepCode("requirement_analyze");
        existing.setStepOrder(1);
        existing.setStatus("pending");
        existing.setProgress(0);
        existing.setRetryCount(0);
        existing.setCreatedAt(LocalDateTime.now());
        existing.setUpdatedAt(LocalDateTime.now());
        List<TaskStepEntity> steps = new ArrayList<>();
        steps.add(existing);

        when(taskRepository.findById("t3")).thenReturn(Optional.of(task));
        when(taskStepRepository.findByTaskIdOrderByStepOrderAsc("t3")).thenReturn(steps);
        when(taskStepRepository.save(any(TaskStepEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(taskRepository.save(any(TaskEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(taskLogRepository.save(any(TaskLogEntity.class))).thenAnswer(i -> i.getArgument(0));

        InternalTaskStepUpdateRequest request = new InternalTaskStepUpdateRequest(
                "codegen_backend", "running", 40, null, null, "generated files", null
        );
        mockMvc.perform(post("/api/internal/task/t3/step-update")
                        .header("X-Internal-Token", "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(taskStepRepository, org.mockito.Mockito.atLeast(2)).save(any(TaskStepEntity.class));
        verify(taskRepository).save(any(TaskEntity.class));
        assertThat(task.getStatus()).isEqualTo("running");
        assertThat(task.getCurrentStep()).isEqualTo("codegen_backend");
    }

    @Test
    void updateTaskStep_shouldRejectInvalidToken() throws Exception {
        InternalTaskStepUpdateRequest request = new InternalTaskStepUpdateRequest(
                "codegen_backend", "running", 40, null, null, null, null
        );
        mockMvc.perform(post("/api/internal/task/t4/step-update")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(taskRepository, never()).save(any(TaskEntity.class));
    }

    private TaskEntity buildTask(String taskId) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId("p1");
        task.setUserId(1L);
        task.setTaskType("generate");
        task.setStatus("queued");
        task.setProgress(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }
}
