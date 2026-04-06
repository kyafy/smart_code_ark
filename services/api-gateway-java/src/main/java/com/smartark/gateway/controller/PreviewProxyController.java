package com.smartark.gateway.controller;

import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.TaskPreviewEntity;
import com.smartark.gateway.db.repo.TaskPreviewRepository;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reverse proxy controller for preview sandbox containers.
 * <p>
 * Routes {@code /api/preview/{taskId}/**} to the Docker container's host port,
 * providing a same-origin access path for the frontend iframe.
 */
@RestController
@RequestMapping("/api/preview")
@Tag(name = "Preview Proxy", description = "Preview container reverse proxy APIs")
public class PreviewProxyController {
    private static final Logger logger = LoggerFactory.getLogger(PreviewProxyController.class);

    private final TaskPreviewRepository taskPreviewRepository;
    private final RestClient restClient;

    public PreviewProxyController(TaskPreviewRepository taskPreviewRepository) {
        this.taskPreviewRepository = taskPreviewRepository;
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, "SmartArk-PreviewProxy/1.0")
                .build();
    }

    @GetMapping("/{taskId}/**")
    @Operation(
            summary = "Proxy preview container content",
            description = "Proxies /api/preview/{taskId}/** to task container host port.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Proxied response"),
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "502",
                            description = "Container unreachable",
                            content = @Content(mediaType = "text/plain"))
            }
    )
    public ResponseEntity<byte[]> proxy(
            @Parameter(description = "Task ID", required = true) @PathVariable String taskId,
            HttpServletRequest request) {

        TaskPreviewEntity preview = taskPreviewRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "预览不存在"));

        if (!"ready".equals(preview.getStatus())) {
            throw new BusinessException(ErrorCodes.CONFLICT, "预览未就绪，当前状态: " + preview.getStatus());
        }

        Integer hostPort = preview.getHostPort();
        if (hostPort == null) {
            throw new BusinessException(ErrorCodes.PREVIEW_START_FAILED, "预览容器端口未记录");
        }

        String proxyPath = extractProxyPath(request, taskId);
        String queryString = request.getQueryString();
        String targetUrl;
        try {
            // Try to resolve the container name first (works when running in the same Docker network)
            java.net.InetAddress.getByName("smartark-preview-" + taskId);
            targetUrl = "http://smartark-preview-" + taskId + ":5173/" + proxyPath;
        } catch (java.net.UnknownHostException e) {
            // Fallback to localhost if not resolvable (e.g. running api-gateway locally)
            targetUrl = "http://localhost:" + hostPort + "/" + proxyPath;
        }
        if (queryString != null && !queryString.isEmpty()) {
            targetUrl += "?" + queryString;
        }

        try {
            ResponseEntity<byte[]> upstream = restClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .toEntity(byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            // Forward content-type from upstream
            if (upstream.getHeaders().getContentType() != null) {
                responseHeaders.setContentType(upstream.getHeaders().getContentType());
            }
            // Forward cache headers
            if (upstream.getHeaders().getCacheControl() != null) {
                responseHeaders.setCacheControl(upstream.getHeaders().getCacheControl());
            }

            return new ResponseEntity<>(upstream.getBody(), responseHeaders,
                    upstream.getStatusCode());
        } catch (RestClientException e) {
            logger.warn("Preview proxy failed for task {}: {}", taskId, e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Preview container unreachable: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Extracts the path portion after /api/preview/{taskId}/.
     * e.g. /api/preview/abc123/assets/index.js → assets/index.js
     */
    private String extractProxyPath(HttpServletRequest request, String taskId) {
        String uri = request.getRequestURI();
        String prefix = "/api/preview/" + taskId + "/";
        if (uri.startsWith(prefix)) {
            return uri.substring(prefix.length());
        }
        // Fallback: strip prefix without trailing slash
        String prefixNoSlash = "/api/preview/" + taskId;
        if (uri.equals(prefixNoSlash)) {
            return "";
        }
        return "";
    }
}
