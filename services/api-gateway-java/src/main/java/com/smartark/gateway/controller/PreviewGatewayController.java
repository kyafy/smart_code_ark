package com.smartark.gateway.controller;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
import com.smartark.gateway.service.PreviewGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@Tag(name = "Preview Gateway", description = "Public preview gateway proxy APIs")
public class PreviewGatewayController {
    private static final Logger logger = LoggerFactory.getLogger(PreviewGatewayController.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final PreviewGatewayService previewGatewayService;
    private final RestClient restClient;

    public PreviewGatewayController(TaskPreviewRepository taskPreviewRepository,
                                    PreviewGatewayService previewGatewayService) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.previewGatewayService = previewGatewayService;
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, "SmartArk-PreviewGateway/1.0")
                .build();
    }

    @GetMapping({"/p/{taskId}/**", "/t/{taskId}/**"})
    @Operation(
            summary = "Proxy preview content",
            description = "Proxies preview static/app content via gateway route.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Proxied response"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "502",
                            description = "Upstream unavailable",
                            content = @Content(mediaType = "text/plain"))
            }
    )
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "Task ID", required = true) @PathVariable String taskId,
            HttpServletRequest request) {

        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "preview not found"));
        if (!"ready".equals(preview.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "preview is not ready: " + preview.getStatus());
        }

        String upstreamBase = previewGatewayService.resolveRoute(taskId)
                .map(route -> route.upstreamBaseUrl().endsWith("/")
                        ? route.upstreamBaseUrl()
                        : route.upstreamBaseUrl() + "/")
                .orElseThrow(() -> new BusinessException(ErrorCodes.PREVIEW_PROXY_FAILED, "preview gateway route not found"));

        String proxyPath = extractProxyPath(request, taskId);
        String queryString = request.getQueryString();
        String targetUrl = upstreamBase + proxyPath;
        if (queryString != null && !queryString.isBlank()) {
            targetUrl += "?" + queryString;
        }

        try {
            ResponseEntity<byte[]> upstream = restClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .toEntity(byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            if (upstream.getHeaders().getContentType() != null) {
                responseHeaders.setContentType(upstream.getHeaders().getContentType());
            }
            if (upstream.getHeaders().getCacheControl() != null) {
                responseHeaders.setCacheControl(upstream.getHeaders().getCacheControl());
            }
            return new ResponseEntity<>(upstream.getBody(), responseHeaders, upstream.getStatusCode());
        } catch (RestClientException e) {
            logger.warn("Preview gateway proxy failed for task {}: {}", taskId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Preview gateway upstream unreachable: " + e.getMessage()).getBytes());
        }
    }

    private String extractProxyPath(HttpServletRequest request, String taskId) {
        String uri = request.getRequestURI();
        String[] prefixes = {
                "/p/" + taskId + "/",
                "/t/" + taskId + "/"
        };
        for (String prefix : prefixes) {
            if (uri.startsWith(prefix)) {
                return uri.substring(prefix.length());
            }
        }
        if (uri.equals("/p/" + taskId) || uri.equals("/t/" + taskId)) {
            return "";
        }
        return "";
    }
}
