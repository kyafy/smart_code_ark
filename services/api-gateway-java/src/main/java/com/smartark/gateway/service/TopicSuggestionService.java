package com.smartark.gateway.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.common.exception.BusinessException;
import com.smartark.gateway.common.exception.ErrorCodes;
import com.smartark.gateway.db.entity.PaperTopicSessionEntity;
import com.smartark.gateway.db.repo.PaperTopicSessionRepository;
import com.smartark.gateway.dto.TopicAdoptRequest;
import com.smartark.gateway.dto.TopicSuggestRequest;
import com.smartark.gateway.dto.TopicSuggestResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class TopicSuggestionService {
    private static final TypeReference<List<TopicSuggestResult.SuggestedTopic>> SUGGESTED_TOPIC_LIST_TYPE =
            new TypeReference<>() { };

    private final ModelService modelService;
    private final PaperTopicSessionRepository topicSessionRepository;
    private final ObjectMapper objectMapper;

    public TopicSuggestionService(ModelService modelService,
                                  PaperTopicSessionRepository topicSessionRepository,
                                  ObjectMapper objectMapper) {
        this.modelService = modelService;
        this.topicSessionRepository = topicSessionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TopicSuggestResult suggest(TopicSuggestRequest request, String userIdText) {
        Long userId = parseUserId(userIdText);
        PaperTopicSessionEntity session = findOrCreateSession(request, userId);
        int nextRound = resolveRound(request.round(), session.getSuggestionRound());
        Map<String, String> vars = Map.of(
                "direction", request.direction(),
                "constraints", Objects.toString(request.constraints(), ""),
                "previousSuggestions", Objects.toString(session.getSuggestedTopicsJson(), "")
        );
        String response = modelService.chat("topic_suggestion", vars);
        List<TopicSuggestResult.SuggestedTopic> suggestions = parseSuggestions(response);
        session.setSuggestedTopicsJson(writeJson(suggestions));
        session.setSuggestionRound(nextRound);
        session.setStatus("suggested");
        session.setUpdatedAt(LocalDateTime.now());
        topicSessionRepository.save(session);
        return new TopicSuggestResult(session.getId(), session.getSuggestionRound(), suggestions);
    }

    @Transactional
    public PaperTopicSessionEntity adopt(TopicAdoptRequest request, String userIdText) {
        Long userId = parseUserId(userIdText);
        PaperTopicSessionEntity session = topicSessionRepository.findByIdAndUserId(request.sessionId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "topic session not found"));

        List<TopicSuggestResult.SuggestedTopic> suggestions = parseSuggestions(session.getSuggestedTopicsJson());
        if (request.selectedIndex() < 0 || request.selectedIndex() >= suggestions.size()) {
            throw new BusinessException(ErrorCodes.VALIDATION_FAILED, "selectedIndex out of range");
        }
        TopicSuggestResult.SuggestedTopic selected = suggestions.get(request.selectedIndex());
        String topic = (request.customTitle() == null || request.customTitle().isBlank())
                ? selected.title()
                : request.customTitle();
        List<String> questions = (request.customQuestions() == null || request.customQuestions().isEmpty())
                ? selected.researchQuestions()
                : request.customQuestions();

        session.setTopic(topic);
        session.setTopicRefined(topic);
        session.setResearchQuestionsJson(writeJson(questions));
        session.setStatus("adopted");
        session.setUpdatedAt(LocalDateTime.now());
        return topicSessionRepository.save(session);
    }

    private PaperTopicSessionEntity findOrCreateSession(TopicSuggestRequest request, Long userId) {
        if (request.sessionId() != null) {
            return topicSessionRepository.findByIdAndUserId(request.sessionId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "topic session not found"));
        }
        LocalDateTime now = LocalDateTime.now();
        PaperTopicSessionEntity session = new PaperTopicSessionEntity();
        session.setTaskId("topic_suggest_" + UUID.randomUUID().toString().replace("-", ""));
        session.setProjectId("paper_topic_suggest_" + userId);
        session.setUserId(userId);
        session.setTopic(request.direction());
        session.setDiscipline(request.direction());
        session.setDegreeLevel("undergraduate");
        session.setMethodPreference(null);
        session.setStatus("suggesting");
        session.setTopicRefined(null);
        session.setResearchQuestionsJson("[]");
        session.setSuggestionRound(0);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return topicSessionRepository.save(session);
    }

    private List<TopicSuggestResult.SuggestedTopic> parseSuggestions(String response) {
        try {
            List<TopicSuggestResult.SuggestedTopic> suggestions = objectMapper.readValue(
                    Objects.toString(response, "[]"),
                    SUGGESTED_TOPIC_LIST_TYPE
            );
            if (suggestions == null || suggestions.isEmpty()) {
                throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "empty suggestions returned from LLM");
            }
            return suggestions;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.MODEL_SERVICE_ERROR, "failed to parse topic suggestions");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.INTERNAL_ERROR, "json serialization failed");
        }
    }

    private Long parseUserId(String userIdText) {
        try {
            return Long.parseLong(userIdText);
        } catch (Exception e) {
            throw new BusinessException(ErrorCodes.UNAUTHORIZED, "unauthorized");
        }
    }

    private int resolveRound(Integer requestRound, Integer currentRound) {
        if (requestRound != null && requestRound > 0) {
            return requestRound;
        }
        return (currentRound == null ? 0 : currentRound) + 1;
    }
}
