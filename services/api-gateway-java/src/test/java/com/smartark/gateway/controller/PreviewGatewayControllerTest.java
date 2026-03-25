package com.smartark.gateway.controller;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.service.PreviewGatewayService;
import com.smartark.gateway.service.PreviewRouteRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PreviewGatewayControllerTest {

    @Mock
    private TaskPreviewRepository taskPreviewRepository;
    @Mock
    private PreviewGatewayService previewGatewayService;

    @Test
    void proxy_returns404WhenPreviewNotFound() {
        PreviewGatewayController controller = new PreviewGatewayController(taskPreviewRepository, previewGatewayService);
        when(taskPreviewRepository.findByTaskId("missing")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/p/missing/index.html");

        assertThatThrownBy(() -> controller.proxy("missing", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("preview not found");
    }

    @Test
    void proxy_returns409WhenPreviewNotReady() {
        PreviewGatewayController controller = new PreviewGatewayController(taskPreviewRepository, previewGatewayService);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("provisioning");
        when(taskPreviewRepository.findByTaskId("task1")).thenReturn(Optional.of(preview));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/p/task1/");

        assertThatThrownBy(() -> controller.proxy("task1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("preview is not ready");
    }

    @Test
    void proxy_returnsProxyErrorWhenRouteMissing() {
        PreviewGatewayController controller = new PreviewGatewayController(taskPreviewRepository, previewGatewayService);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("ready");
        when(taskPreviewRepository.findByTaskId("task2")).thenReturn(Optional.of(preview));
        when(previewGatewayService.resolveRoute("task2")).thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/p/task2/");

        assertThatThrownBy(() -> controller.proxy("task2", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("route not found");
    }

    @Test
    void proxy_supportsTPrefixPath() {
        PreviewGatewayController controller = new PreviewGatewayController(taskPreviewRepository, previewGatewayService);
        TaskPreviewEntity preview = new TaskPreviewEntity();
        preview.setStatus("ready");
        when(taskPreviewRepository.findByTaskId("task3")).thenReturn(Optional.of(preview));
        when(previewGatewayService.resolveRoute("task3")).thenReturn(Optional.of(
                new PreviewRouteRegistry.RouteEntry(
                        "task3",
                        "http://localhost:30099",
                        LocalDateTime.now().plusMinutes(30),
                        LocalDateTime.now()
                )));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/t/task3/assets/index.js");

        try {
            controller.proxy("task3", request);
        } catch (Exception e) {
            assertThat(e).isNotInstanceOf(BusinessException.class);
        }
    }
}
