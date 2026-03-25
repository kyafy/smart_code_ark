package com.smartark.gateway.controller;

import com.smartark.gateway.common.response.ApiResponse;
import com.smartark.gateway.dto.ChatReplyResult;
import com.smartark.gateway.dto.ChatSendRequest;
import com.smartark.gateway.dto.ChatStartRequest;
import com.smartark.gateway.dto.ChatStartResult;
import com.smartark.gateway.service.ChatService;
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
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/start")
    public ApiResponse<ChatStartResult> start(@Valid @RequestBody ChatStartRequest request) {
        return ApiResponse.success(chatService.start(request));
    }

    @PostMapping
    public SseEmitter chat(@Valid @RequestBody ChatSendRequest request) {
        return chatService.streamChat(request);
    }

    @GetMapping("/sessions")
    public ApiResponse<List<Map<String, Object>>> getHistorySessions() {
        return ApiResponse.success(chatService.getHistorySessions());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<ChatReplyResult> getSessionMessages(@PathVariable String sessionId) {
        return ApiResponse.success(chatService.getSessionMessages(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Boolean> deleteSession(@PathVariable String sessionId) {
        chatService.deleteSession(sessionId);
        return ApiResponse.success(true);
    }
}
