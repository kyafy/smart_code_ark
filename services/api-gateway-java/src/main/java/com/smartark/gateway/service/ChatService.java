package com.smartark.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.auth.RequestContext;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.common.util.HashUtil;
import com.smartark.gateway.common.util.SensitiveDataSanitizer;
import com.smartark.gateway.db.entity.ChatMessageEntity;
import com.smartark.gateway.db.entity.ChatSessionEntity;
import com.smartark.gateway.db.entity.PromptHistoryEntity;
import com.smartark.gateway.db.repo.ChatMessageRepository;
import com.smartark.gateway.db.repo.ChatSessionRepository;
import com.smartark.gateway.db.repo.PromptHistoryRepository;
import com.smartark.gateway.dto.ChatReplyResult;
import com.smartark.gateway.dto.ChatSendRequest;
import com.smartark.gateway.dto.ChatStartRequest;
import com.smartark.gateway.dto.ChatStartResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);
    private static final String TEMPLATE_KEY_CHAT = "chat";
    private static final int TEMPLATE_VERSION = 1;

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PromptHistoryRepository promptHistoryRepository;
    private final ModelService modelService;
    private final ObjectMapper objectMapper;

    public ChatService(
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            PromptHistoryRepository promptHistoryRepository,
            ModelService modelService,
            ObjectMapper objectMapper
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.promptHistoryRepository = promptHistoryRepository;
        this.modelService = modelService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChatStartResult start(ChatStartRequest request) {
        Long userId = requireUserId();
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        LocalDateTime now = LocalDateTime.now();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setId(sessionId);
        session.setUserId(userId);
        session.setProjectId(null);
        session.setTitle(request.title());
        session.setProjectType(request.projectType());
        session.setDescription(request.description() == null ? "" : request.description());
        session.setStatus("active");
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        chatSessionRepository.save(session);

        return new ChatStartResult(sessionId, "active");
    }

    public List<Map<String, Object>> getHistorySessions() {
        Long userId = requireUserId();
        return chatSessionRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(s -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("sessionId", s.getId());
                    map.put("title", s.getTitle());
                    map.put("projectType", s.getProjectType());
                    map.put("status", s.getStatus());
                    map.put("updatedAt", s.getUpdatedAt().toString());
                    return map;
                })
                .toList();
    }

    public ChatReplyResult getSessionMessages(String sessionId) {
        Long userId = requireUserId();
        ChatSessionEntity session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该会话");
        }

        List<ChatMessageEntity> historyEntities = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<Map<String, String>> history = new ArrayList<>();
        Map<String, Object> lastExtractedRequirements = null;

        for (ChatMessageEntity m : historyEntities) {
            String role = "assistant".equalsIgnoreCase(m.getSpeaker()) ? "assistant" : "user";
            history.add(Map.of("role", role, "content", m.getMessage()));
            
            // Try to extract requirements from the last assistant message
            if ("assistant".equals(role)) {
                Map<String, Object> reqs = modelService.extractRequirements(m.getMessage());
                if (reqs != null) {
                    lastExtractedRequirements = reqs;
                }
            }
        }

        return new ChatReplyResult(
                session.getId(),
                history,
                lastExtractedRequirements,
                session.getCreatedAt().toString(),
                session.getUpdatedAt().toString()
        );
    }

    @Transactional
    public ChatReplyResult chat(ChatSendRequest request) {
        Long userId = requireUserId();
        ChatSessionEntity session = chatSessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该会话");
        }

        LocalDateTime now = LocalDateTime.now();

        ChatMessageEntity userMsg = new ChatMessageEntity();
        userMsg.setSessionId(session.getId());
        userMsg.setSpeaker("user");
        userMsg.setMessage(request.message());
        userMsg.setTokenUsed(0);
        userMsg.setCreatedAt(now);
        chatMessageRepository.save(userMsg);

        List<ChatMessageEntity> historyEntities = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessageEntity m : historyEntities) {
            String role = "assistant".equalsIgnoreCase(m.getSpeaker()) ? "assistant" : "user";
            history.add(Map.of("role", role, "content", m.getMessage()));
        }

        long t0 = System.nanoTime();
        ModelService.ModelResult modelResult;
        String status = "success";
        String errorCode = null;
        String errorMessage = null;
        try {
            modelResult = modelService.chatReply(session.getTitle(), session.getProjectType(), request.message(), history);
        } catch (BusinessException ex) {
            status = "failed";
            errorCode = String.valueOf(ex.getCode());
            errorMessage = ex.getMessage();
            modelResult = new ModelService.ModelResult("unknown", "模型服务暂不可用，请稍后再试。", List.of("用户", "核心业务", "管理后台"), 0, 0);
        }
        int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000L);

        ChatMessageEntity assistantMsg = new ChatMessageEntity();
        assistantMsg.setSessionId(session.getId());
        assistantMsg.setSpeaker("assistant");
        assistantMsg.setMessage(modelResult.reply());
        assistantMsg.setTokenUsed(modelResult.tokenOutput());
        assistantMsg.setCreatedAt(LocalDateTime.now());
        chatMessageRepository.save(assistantMsg);

        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        writePromptHistory(session, request.message(), history, modelResult, latencyMs, status, errorCode, errorMessage);

        return new ChatReplyResult(
                session.getId(),
                history,
                null,
                session.getCreatedAt().toString(),
                session.getUpdatedAt().toString()
        );
    }

    public SseEmitter streamChat(ChatSendRequest request) {
        Long userId = requireUserId();
        ChatSessionEntity session = chatSessionRepository.findById(request.sessionId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "会话不存在"));
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(ErrorCodes.FORBIDDEN, "无权限访问该会话");
        }

        LocalDateTime now = LocalDateTime.now();
        ChatMessageEntity userMsg = new ChatMessageEntity();
        userMsg.setSessionId(session.getId());
        userMsg.setSpeaker("user");
        userMsg.setMessage(request.message());
        userMsg.setTokenUsed(0);
        userMsg.setCreatedAt(now);
        chatMessageRepository.save(userMsg);

        List<ChatMessageEntity> historyEntities = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessageEntity m : historyEntities) {
            String role = "assistant".equalsIgnoreCase(m.getSpeaker()) ? "assistant" : "user";
            history.add(Map.of("role", role, "content", m.getMessage()));
        }

        SseEmitter emitter = new SseEmitter(180000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(t -> closed.set(true));
        long t0 = System.nanoTime();
        
        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                logger.info("Starting stream chat for session: {}", session.getId());
                StringBuilder fullReply = new StringBuilder();
                modelService.streamChatReply(session.getTitle(), session.getProjectType(), request.message(), history,
                        content -> {
                            if (closed.get()) {
                                return;
                            }
                            try {
                                logger.debug("Sending delta content: {}", content);
                                emitter.send(SseEmitter.event().name("delta").data(content));
                                fullReply.append(content);
                            } catch (IOException | IllegalStateException e) {
                                closed.set(true);
                                emitter.complete();
                            }
                        },
                        () -> {
                            if (closed.get()) {
                                return;
                            }
                            try {
                                String reply = fullReply.toString();
                                logger.info("Stream chat completed. Full reply length: {}", reply.length());
                                
                                Map<String, Object> extractedRequirements = modelService.extractRequirements(reply);
                                
                                // Clean up reply to hide JSON block if present
                                String cleanReply = reply;
                                if (extractedRequirements != null) {
                                    int start = reply.lastIndexOf("```json");
                                    if (start != -1) {
                                        cleanReply = reply.substring(0, start).trim();
                                    }
                                }
                                
                                ChatMessageEntity assistantMsg = new ChatMessageEntity();
                                assistantMsg.setSessionId(session.getId());
                                assistantMsg.setSpeaker("assistant");
                                assistantMsg.setMessage(cleanReply);
                                assistantMsg.setTokenUsed(modelService.estimateTokens(reply));
                                assistantMsg.setCreatedAt(LocalDateTime.now());
                                chatMessageRepository.save(assistantMsg);

                                session.setUpdatedAt(LocalDateTime.now());
                                chatSessionRepository.save(session);
                                
                                int latencyMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
                                writePromptHistory(session, request.message(), history, new ModelService.ModelResult(
                                        "qwen-plus", cleanReply, new ArrayList<>(), 0, assistantMsg.getTokenUsed()
                                ), latencyMs, "success", null, null);

                                List<Map<String, String>> updatedMessages = new ArrayList<>(history);
                                updatedMessages.add(Map.of("role", "user", "content", request.message()));
                                updatedMessages.add(Map.of("role", "assistant", "content", cleanReply));

                                emitter.send(SseEmitter.event().name("result").data(new ChatReplyResult(
                                        session.getId(),
                                        updatedMessages,
                                        extractedRequirements,
                                        session.getCreatedAt().toString(),
                                        session.getUpdatedAt().toString()
                                )));
                                emitter.complete();
                            } catch (IOException | IllegalStateException e) {
                                closed.set(true);
                                emitter.complete();
                            }
                        },
                        e -> {
                            if (closed.get()) {
                                return;
                            }
                            logger.error("Error in stream chat", e);
                            emitter.completeWithError(e);
                        }
                );
            } catch (Exception e) {
                logger.error("Unexpected error in stream executor", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void writePromptHistory(
            ChatSessionEntity session,
            String userMessage,
            List<Map<String, String>> history,
            ModelService.ModelResult result,
            int latencyMs,
            String status,
            String errorCode,
            String errorMessage
    ) {
        try {
            Map<String, Object> input = Map.of(
                    "sessionId", session.getId(),
                    "title", session.getTitle(),
                    "projectType", session.getProjectType(),
                    "message", userMessage,
                    "history", history
            );
            Map<String, Object> output = Map.of(
                    "reply", result.reply(),
                    "draftModules", result.draftModules()
            );

            String inputJsonRaw = objectMapper.writeValueAsString(input);
            String outputJsonRaw = objectMapper.writeValueAsString(output);
            String inputJson = SensitiveDataSanitizer.sanitizeJsonString(objectMapper, inputJsonRaw);
            String outputJson = SensitiveDataSanitizer.sanitizeJsonString(objectMapper, outputJsonRaw);
            String requestHash = HashUtil.sha256Hex(inputJson);

            PromptHistoryEntity ph = new PromptHistoryEntity();
            ph.setTaskId(null);
            ph.setProjectId(session.getProjectId());
            ph.setTemplateKey(TEMPLATE_KEY_CHAT);
            ph.setVersionNo(TEMPLATE_VERSION);
            ph.setModel(result.model());
            ph.setRequestHash(requestHash);
            ph.setInputJson(inputJson);
            ph.setOutputJson(outputJson);
            ph.setTokenInput(result.tokenInput());
            ph.setTokenOutput(result.tokenOutput());
            ph.setLatencyMs(latencyMs);
            ph.setStatus(status);
            ph.setErrorCode(errorCode);
            ph.setErrorMessage(errorMessage == null ? null : SensitiveDataSanitizer.sanitizeText(errorMessage));
            ph.setCreatedAt(LocalDateTime.now());
            promptHistoryRepository.save(ph);
        } catch (Exception ignored) {
            return;
        }
    }

    private Long requireUserId() {
        String userId = RequestContext.getUserId();
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
        try {
            return Long.parseLong(userId);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "未登录");
        }
    }
}
