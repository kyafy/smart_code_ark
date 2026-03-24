package com.smartark.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartark.gateway.db.entity.TaskStepMemoryEntity;
import com.smartark.gateway.db.repo.TaskStepMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class StepMemoryService {
    private static final Logger logger = LoggerFactory.getLogger(StepMemoryService.class);

    private final TaskStepMemoryRepository repository;
    private final ObjectMapper objectMapper;

    public StepMemoryService(TaskStepMemoryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void save(String taskId, String stepCode, String key, Object value) {
        if (taskId == null || stepCode == null || key == null || value == null) {
            return;
        }
        try {
            String json = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
            Optional<TaskStepMemoryEntity> existing = repository.findByTaskIdAndStepCodeAndMemoryKey(taskId, stepCode, key);
            TaskStepMemoryEntity entity;
            if (existing.isPresent()) {
                entity = existing.get();
                entity.setMemoryValue(json);
                entity.setUpdatedAt(LocalDateTime.now());
            } else {
                entity = new TaskStepMemoryEntity();
                entity.setTaskId(taskId);
                entity.setStepCode(stepCode);
                entity.setMemoryKey(key);
                entity.setMemoryValue(json);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
            }
            repository.save(entity);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize step memory: taskId={}, stepCode={}, key={}", taskId, stepCode, key, e);
        }
    }

    public <T> Optional<T> load(String taskId, String stepCode, String key, Class<T> type) {
        return repository.findByTaskIdAndStepCodeAndMemoryKey(taskId, stepCode, key)
                .map(entity -> {
                    try {
                        if (type == String.class) {
                            @SuppressWarnings("unchecked")
                            T result = (T) entity.getMemoryValue();
                            return result;
                        }
                        return objectMapper.readValue(entity.getMemoryValue(), type);
                    } catch (JsonProcessingException e) {
                        logger.warn("Failed to deserialize step memory: taskId={}, stepCode={}, key={}", taskId, stepCode, key, e);
                        return null;
                    }
                });
    }

    public Optional<String> loadRaw(String taskId, String stepCode, String key) {
        return repository.findByTaskIdAndStepCodeAndMemoryKey(taskId, stepCode, key)
                .map(TaskStepMemoryEntity::getMemoryValue);
    }

    @Transactional
    public void clearStep(String taskId, String stepCode) {
        repository.deleteByTaskIdAndStepCode(taskId, stepCode);
    }

    @Transactional
    public void clearTask(String taskId) {
        repository.deleteByTaskId(taskId);
    }
}
