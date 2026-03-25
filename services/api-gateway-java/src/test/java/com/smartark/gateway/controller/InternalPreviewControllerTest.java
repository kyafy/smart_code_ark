package com.smartark.gateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.dto.PreviewStatusCallback;
import com.smartark.gateway.dto.TaskPreviewResult;
import com.smartark.gateway.service.PreviewSseRegistry;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalPreviewControllerTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private PreviewSseRegistry previewSseRegistry;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        InternalPreviewController controller = new InternalPreviewController(taskPreviewRepository, previewSseRegistry);
        ReflectionTestUtils.setField(controller, "internalToken", "test-token-123");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void updateStatus_shouldUpdatePreviewAndBroadcastSse() throws Exception {
        TaskPreviewEntity preview = buildPreview("t1", "provisioning");
        when(taskPreviewRepository.findByTaskId("t1")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PreviewStatusCallback callback = new PreviewStatusCallback("ready", null, "http://localhost:30001", null, null);

        mockMvc.perform(post("/internal/preview/t1/status")
                        .header("X-Internal-Token", "test-token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ready");
        assertThat(captor.getValue().getPreviewUrl()).isEqualTo("http://localhost:30001");

        verify(previewSseRegistry).broadcast(eq("t1"), any(TaskPreviewResult.class));
    }

    @Test
    void updateStatus_shouldUpdatePhaseOnly() throws Exception {
        TaskPreviewEntity preview = buildPreview("t2", "provisioning");
        when(taskPreviewRepository.findByTaskId("t2")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PreviewStatusCallback callback = new PreviewStatusCallback(null, "install_deps", null, null, null);

        mockMvc.perform(post("/internal/preview/t2/status")
                        .header("X-Internal-Token", "test-token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk());

        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository).save(captor.capture());
        assertThat(captor.getValue().getPhase()).isEqualTo("install_deps");
        assertThat(captor.getValue().getStatus()).isEqualTo("provisioning"); // unchanged
    }

    @Test
    void updateStatus_shouldUpdateErrorFields() throws Exception {
        TaskPreviewEntity preview = buildPreview("t3", "provisioning");
        when(taskPreviewRepository.findByTaskId("t3")).thenReturn(Optional.of(preview));
        when(taskPreviewRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        PreviewStatusCallback callback = new PreviewStatusCallback("failed", "health_check", null, "Connection refused", 3104);

        mockMvc.perform(post("/internal/preview/t3/status")
                        .header("X-Internal-Token", "test-token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk());

        ArgumentCaptor<TaskPreviewEntity> captor = ArgumentCaptor.forClass(TaskPreviewEntity.class);
        verify(taskPreviewRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("failed");
        assertThat(captor.getValue().getLastError()).isEqualTo("Connection refused");
        assertThat(captor.getValue().getLastErrorCode()).isEqualTo(3104);
    }

    @Test
    void updateStatus_shouldRejectInvalidToken() throws Exception {
        PreviewStatusCallback callback = new PreviewStatusCallback("ready", null, null, null, null);

        mockMvc.perform(post("/internal/preview/t4/status")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(taskPreviewRepository, never()).save(any());
        verify(previewSseRegistry, never()).broadcast(any(), any());
    }

    @Test
    void updateStatus_shouldRejectMissingToken() throws Exception {
        PreviewStatusCallback callback = new PreviewStatusCallback("ready", null, null, null, null);

        mockMvc.perform(post("/internal/preview/t5/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(taskPreviewRepository, never()).save(any());
    }

    @Test
    void updateStatus_shouldReturn404WhenPreviewNotFound() throws Exception {
        when(taskPreviewRepository.findByTaskId("not-exist")).thenReturn(Optional.empty());

        PreviewStatusCallback callback = new PreviewStatusCallback("ready", null, null, null, null);

        mockMvc.perform(post("/internal/preview/not-exist/status")
                        .header("X-Internal-Token", "test-token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(callback)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    private TaskPreviewEntity buildPreview(String taskId, String status) {
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setId(1L);
        preview.setTaskId(taskId);
        preview.setProjectId("p1");
        preview.setUserId(1L);
        preview.setStatus(status);
        preview.setCreatedAt(LocalDateTime.now());
        preview.setUpdatedAt(LocalDateTime.now());
        return preview;
    }
}
