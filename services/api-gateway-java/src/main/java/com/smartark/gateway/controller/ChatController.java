package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.ChatReplyResult;
import com.smartark.gateway.dto.ChatSendRequest;
import com.smartark.gateway.dto.ChatStartRequest;
import com.smartark.gateway.dto.ChatStartResult;
import com.smartark.gateway.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/chat", "/api/v1/chat"})
@Tag(name = "Chat", description = "Conversation session management and streaming chat APIs")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start chat session")
    public ApiResponse<ChatStartResult> start(@Valid @RequestBody ChatStartRequest request) {
        return ApiResponse.success(chatService.start(request));
    }

    @PostMapping
    @Operation(
            summary = "Stream chat reply",
            description = "SSE endpoint for streaming assistant response.",
            responses = {
                    @io.swagger.v3.oas.annotations.responses.ApiResponse(
                            responseCode = "200",
                            description = "SSE stream",
                            content = @Content(mediaType = "text/event-stream")
                    )
            }
    )
    public SseEmitter chat(@Valid @RequestBody ChatSendRequest request) {
        return chatService.streamChat(request);
    }

    @GetMapping("/sessions")
    @Operation(summary = "List chat sessions")
    public ApiResponse<List<Map<String, Object>>> getHistorySessions() {
        return ApiResponse.success(chatService.getHistorySessions());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    @Operation(summary = "Get session messages")
    public ApiResponse<ChatReplyResult> getSessionMessages(
            @Parameter(description = "Session ID", required = true) @PathVariable String sessionId) {
        return ApiResponse.success(chatService.getSessionMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "Delete chat session")
    public ApiResponse<Boolean> deleteSession(
            @Parameter(description = "Session ID", required = true) @PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return ApiResponse.success(true);
    }
}
