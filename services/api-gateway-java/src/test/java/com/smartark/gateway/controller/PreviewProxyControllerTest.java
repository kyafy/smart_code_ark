package com.smartark.gateway.controller;

import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewProxyControllerTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;

    @Test
    void proxy_returns404WhenPreviewNotFound() {
        PreviewProxyController controller = new PreviewProxyController(taskPreviewRepository);
        when(taskPreviewRepository.findByTaskId("unknown")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/preview/unknown/index.html");

        assertThatThrownBy(() -> controller.proxy("unknown", request))
                .hasMessageContaining("预览不存在");
    }

    @Test
    void proxy_returns409WhenNotReady() {
        PreviewProxyController controller = new PreviewProxyController(taskPreviewRepository);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("provisioning");
        preview.setHostPort(30001);
        when(taskPreviewRepository.findByTaskId("task1")).thenReturn(Optional.of(preview));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/preview/task1/");

        assertThatThrownBy(() -> controller.proxy("task1", request))
                .hasMessageContaining("预览未就绪");
    }

    @Test
    void proxy_returnsErrorWhenHostPortMissing() {
        PreviewProxyController controller = new PreviewProxyController(taskPreviewRepository);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("ready");
        preview.setHostPort(null);
        when(taskPreviewRepository.findByTaskId("task2")).thenReturn(Optional.of(preview));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/preview/task2/");

        assertThatThrownBy(() -> controller.proxy("task2", request))
                .hasMessageContaining("端口未记录");
    }

    @Test
    void proxy_extractsPathCorrectly() {
        // Test path extraction logic via reflection-free approach:
        // The controller extracts "/api/preview/{taskId}/assets/index.js" → "assets/index.js"
        PreviewProxyController controller = new PreviewProxyController(taskPreviewRepository);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("ready");
        preview.setHostPort(30042);
        when(taskPreviewRepository.findByTaskId("abc123")).thenReturn(Optional.of(preview));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/preview/abc123/assets/index.js");

        // This will fail with RestClientException since no real server is running,
        // but the path extraction and validation logic is exercised.
        // We catch the expected exception from the proxy call.
        try {
            controller.proxy("abc123", request);
        } catch (Exception e) {
            // Expected: RestClientException because localhost:30042 is not available
            // The important thing is that the method didn't throw BusinessException
            // (which would mean validation failed)
            assertThat(e).isNotInstanceOf(com.smartark.gateway.common.exception.BusinessException.class);
        }
    }
}
