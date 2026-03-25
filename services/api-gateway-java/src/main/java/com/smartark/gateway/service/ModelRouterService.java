package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import com.smartark.gateway.db.repo.ModelRegistryRepository;
import com.smartark.gateway.db.repo.ModelUsageDailyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model routing service: resolves which model to use based on role, daily usage limits,
 * and priority. Supports auto-switch when a model approaches its daily token limit.
 */
@Service
public class ModelRouterService {
    private static final Logger logger = LoggerFactory.getLogger(ModelRouterService.class);

    private final ModelRegistryRepository registryRepository;
    private final ModelUsageDailyRepository usageDailyRepository;
    private final ModelCredentialCryptoService credentialCryptoService;
    private final String defaultChatModel;
    private final String defaultCodeModel;
    private final String defaultPaperModel;
    private final String defaultEmbeddingModel;

    public record ModelConnection(String baseUrl, String apiKey) {
    }

    public ModelRouterService(
            ModelRegistryRepository registryRepository,
            ModelUsageDailyRepository usageDailyRepository,
            ModelCredentialCryptoService credentialCryptoService,
            @Value("${smartark.model.chat-model:Qwen3.5-Plus}") String defaultChatModel,
            @Value("${smartark.model.code-model:qwen-plus}") String defaultCodeModel,
            @Value("${smartark.model.paper-model:Qwen3.5-Plus}") String defaultPaperModel,
            @Value("${smartark.rag.embedding-model:text-embedding-v4}") String defaultEmbeddingModel) {
        this.registryRepository = registryRepository;
        this.usageDailyRepository = usageDailyRepository;
        this.credentialCryptoService = credentialCryptoService;
        this.defaultChatModel = defaultChatModel;
        this.defaultCodeModel = defaultCodeModel;
        this.defaultPaperModel = defaultPaperModel;
        this.defaultEmbeddingModel = defaultEmbeddingModel;
    }

    /**
     * Resolve the best available model for the given role.
     * Falls back to application.yml default if no registry entries exist.
     *
     * @param role "chat", "code", "paper" or "embedding"
     * @return model name to use
     */
    public String resolve(String role) {
        List<ModelRegistryEntity> candidates = registryRepository
                .findByModelRoleAndEnabledTrueOrderByPriorityAsc(role);

        if (candidates.isEmpty()) {
            return fallbackModel(role, "no registered models");
        }

        LocalDate today = LocalDate.now();
        List<ModelRegistryEntity> eligible = candidates.stream()
                .filter(this::hasConnectionConfig)
                .toList();
        if (eligible.isEmpty()) {
            return fallbackModel(role, "no model has base_url/api_key configured");
        }
        if (eligible.size() != candidates.size()) {
            logger.info("Skipped {} models without connection config for role={}",
                    candidates.size() - eligible.size(), role);
        }
        for (ModelRegistryEntity candidate : eligible) {
            if (isWithinLimit(candidate, today)) {
                logger.debug("Resolved model for role={}: {} (priority={})",
                        role, candidate.getModelName(), candidate.getPriority());
                return candidate.getModelName();
            }
            logger.info("Model {} exceeded daily limit, trying next candidate", candidate.getModelName());
        }

        // All models exceeded limits — use the highest priority one anyway with a warning
        String bestEffort = eligible.get(0).getModelName();
        logger.warn("All models for role={} exceeded daily limits, using best-effort: {}", role, bestEffort);
        return bestEffort;
    }

    private boolean hasConnectionConfig(ModelRegistryEntity model) {
        return model.getBaseUrl() != null && !model.getBaseUrl().isBlank()
                && model.getApiKeyCiphertext() != null && !model.getApiKeyCiphertext().isBlank();
    }

    private String fallbackModel(String role, String reason) {
        String fallback;
        if ("chat".equals(role)) {
            fallback = defaultChatModel;
        } else if ("paper".equals(role)) {
            fallback = defaultPaperModel;
        } else if ("embedding".equals(role)) {
            fallback = defaultEmbeddingModel;
        } else {
            fallback = defaultCodeModel;
        }
        logger.info("Fallback model for role={} to {} ({})", role, fallback, reason);
        return fallback;
    }

    /**
     * Check if the model is within its daily token limit.
     */
    private boolean isWithinLimit(ModelRegistryEntity model, LocalDate date) {
        long limit = model.getDailyTokenLimit();
        if (limit <= 0) {
            return true; // unlimited
        }
        Optional<ModelUsageDailyEntity> usage = usageDailyRepository
                .findByModelNameAndUsageDate(model.getModelName(), date);
        long totalUsed = usage.map(ModelUsageDailyEntity::getTokenTotal).orElse(0L);
        return totalUsed < limit;
    }

    /**
     * Record token usage after a model call completes.
     */
    @Transactional
    public void recordUsage(String modelName, int tokenInput, int tokenOutput) {
        LocalDate today = LocalDate.now();
        ModelUsageDailyEntity usage = usageDailyRepository
                .findByModelNameAndUsageDate(modelName, today)
                .orElse(null);

        if (usage == null) {
            usage = new ModelUsageDailyEntity();
            usage.setModelName(modelName);
            usage.setUsageDate(today);
            usage.setCallCount(1L);
            usage.setTokenInput((long) Math.max(0, tokenInput));
            usage.setTokenOutput((long) Math.max(0, tokenOutput));
            usage.setTokenTotal((long) Math.max(0, tokenInput) + Math.max(0, tokenOutput));
            usage.setUpdatedAt(LocalDateTime.now());
        } else {
            usage.setCallCount(usage.getCallCount() + 1);
            usage.setTokenInput(usage.getTokenInput() + Math.max(0, tokenInput));
            usage.setTokenOutput(usage.getTokenOutput() + Math.max(0, tokenOutput));
            usage.setTokenTotal(usage.getTokenTotal() + Math.max(0, tokenInput) + Math.max(0, tokenOutput));
            usage.setUpdatedAt(LocalDateTime.now());
        }
        usageDailyRepository.save(usage);
    }

    // ==================== Admin API support ====================

    public List<ModelRegistryEntity> listAll() {
        return registryRepository.findAllByOrderByPriorityAsc();
    }

    public Optional<ModelRegistryEntity> findByName(String modelName) {
        return registryRepository.findByModelName(modelName);
    }

    public Optional<ModelConnection> resolveConnection(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return registryRepository.findByModelName(modelName)
                .filter(model -> model.getEnabled() == null || model.getEnabled())
                .flatMap(model -> {
                    String base = model.getBaseUrl();
                    String cipher = model.getApiKeyCiphertext();
                    if (base == null || base.isBlank() || cipher == null || cipher.isBlank()) {
                        return Optional.empty();
                    }
                    try {
                        String apiKey = credentialCryptoService.decrypt(cipher);
                        if (apiKey == null || apiKey.isBlank()) {
                            return Optional.empty();
                        }
                        return Optional.of(new ModelConnection(base.trim(), apiKey.trim()));
                    } catch (Exception e) {
                        logger.warn("Failed to resolve model connection for {}", modelName);
                        return Optional.empty();
                    }
                });
    }

    @Transactional
    public ModelRegistryEntity saveModel(ModelRegistryEntity entity) {
        LocalDateTime now = LocalDateTime.now();
        if (entity.getId() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        return registryRepository.save(entity);
    }

    @Transactional
    public ModelRegistryEntity upsertModel(String modelName, String displayName, String provider,
                                           String modelRole, Long dailyTokenLimit, Integer priority,
                                           Boolean enabled, String baseUrl, String apiKeyPlaintext) {
        ModelRegistryEntity entity = registryRepository.findByModelName(modelName).orElse(null);
        if (entity == null) {
            entity = new ModelRegistryEntity();
            entity.setModelName(modelName);
            entity.setCreatedAt(LocalDateTime.now());
        }
        if (displayName != null) entity.setDisplayName(displayName);
        if (provider != null) entity.setProvider(provider);
        if (modelRole != null) entity.setModelRole(modelRole);
        if (dailyTokenLimit != null) entity.setDailyTokenLimit(dailyTokenLimit);
        if (priority != null) entity.setPriority(priority);
        if (enabled != null) entity.setEnabled(enabled);
        if (baseUrl != null) entity.setBaseUrl(baseUrl.isBlank() ? null : baseUrl.trim());
        if (apiKeyPlaintext != null) {
            if (apiKeyPlaintext.isBlank()) {
                entity.setApiKeyCiphertext(null);
                entity.setApiKeyMasked(null);
                entity.setCryptoVersion(null);
            } else {
                entity.setApiKeyCiphertext(credentialCryptoService.encrypt(apiKeyPlaintext.trim()));
                entity.setApiKeyMasked(credentialCryptoService.mask(apiKeyPlaintext.trim()));
                entity.setCryptoVersion(credentialCryptoService.version());
            }
        }
        entity.setUpdatedAt(LocalDateTime.now());
        return registryRepository.save(entity);
    }

    @Transactional
    public void deleteModel(String modelName) {
        registryRepository.findByModelName(modelName).ifPresent(registryRepository::delete);
    }

    /**
     * Get today's usage for all registered models, keyed by model name.
     */
    public Map<String, ModelUsageDailyEntity> getTodayUsage() {
        LocalDate today = LocalDate.now();
        List<ModelUsageDailyEntity> usages = usageDailyRepository.findByUsageDate(today);
        Map<String, ModelUsageDailyEntity> result = new LinkedHashMap<>();
        for (ModelUsageDailyEntity u : usages) {
            result.put(u.getModelName(), u);
        }
        return result;
    }

    /**
     * Get usage history for a date range.
     */
    public List<ModelUsageDailyEntity> getUsageHistory(String modelName, LocalDate start, LocalDate end) {
        if (modelName != null && !modelName.isBlank()) {
            return usageDailyRepository.findByModelNameAndUsageDateBetweenOrderByUsageDateAsc(modelName, start, end);
        }
        return usageDailyRepository.findByUsageDateBetweenOrderByUsageDateAsc(start, end);
    }

    /**
     * Dashboard summary: all models with their config + today's usage + remaining quota.
     */
    public List<Map<String, Object>> dashboard() {
        List<ModelRegistryEntity> models = registryRepository.findAllByOrderByPriorityAsc();
        Map<String, ModelUsageDailyEntity> todayUsage = getTodayUsage();

        return models.stream().map(model -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelName", model.getModelName());
            item.put("displayName", model.getDisplayName());
            item.put("provider", model.getProvider());
            item.put("modelRole", model.getModelRole());
            item.put("dailyTokenLimit", model.getDailyTokenLimit());
            item.put("priority", model.getPriority());
            item.put("enabled", model.getEnabled());

            ModelUsageDailyEntity usage = todayUsage.get(model.getModelName());
            long todayTotal = usage != null ? usage.getTokenTotal() : 0;
            long todayCalls = usage != null ? usage.getCallCount() : 0;
            long todayInput = usage != null ? usage.getTokenInput() : 0;
            long todayOutput = usage != null ? usage.getTokenOutput() : 0;

            item.put("todayTokenTotal", todayTotal);
            item.put("todayCallCount", todayCalls);
            item.put("todayTokenInput", todayInput);
            item.put("todayTokenOutput", todayOutput);

            long limit = model.getDailyTokenLimit();
            if (limit > 0) {
                item.put("todayRemaining", Math.max(0, limit - todayTotal));
                item.put("todayUsagePercent", Math.min(100, (int) (todayTotal * 100 / limit)));
            } else {
                item.put("todayRemaining", -1); // unlimited
                item.put("todayUsagePercent", 0);
            }
            return item;
        }).toList();
    }
}
