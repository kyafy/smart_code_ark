package com.smartark.gateway.service;

import com.smartark.gateway.db.entity.ModelRegistryEntity;
import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import com.smartark.gateway.db.repo.ModelRegistryRepository;
import com.smartark.gateway.db.repo.ModelUsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModelRouterServiceTest {

    @Mock
    private ModelRegistryRepository registryRepository;
    @Mock
    private ModelUsageDailyRepository usageDailyRepository;
    @Mock
    private ModelCredentialCryptoService credentialCryptoService;

    private ModelRouterService service;

    @BeforeEach
    void setUp() {
        service = new ModelRouterService(registryRepository, usageDailyRepository, credentialCryptoService,
                "Qwen3.5-Plus", "qwen-plus", "text-embedding-v4");
    }

    @Test
    void resolve_fallsBackToConfigWhenNoRegisteredModels() {
        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of());

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-plus");
    }

    @Test
    void resolve_fallsBackToChatModelConfig() {
        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("chat"))
                .thenReturn(List.of());

        String result = service.resolve("chat");

        assertThat(result).isEqualTo("Qwen3.5-Plus");
    }

    @Test
    void resolve_returnsHighestPriorityModelWithinLimit() {
        ModelRegistryEntity model1 = buildModel("qwen-max", "code", 1_000_000L, 10);
        ModelRegistryEntity model2 = buildModel("qwen-plus", "code", 2_000_000L, 20);

        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of(model1, model2));
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-max"), any()))
                .thenReturn(Optional.of(buildUsage("qwen-max", 500_000L)));

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-max");
    }

    @Test
    void resolve_skipsExceededModelAndPicksNext() {
        ModelRegistryEntity model1 = buildModel("qwen-max", "code", 1_000_000L, 10);
        ModelRegistryEntity model2 = buildModel("qwen-plus", "code", 2_000_000L, 20);

        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of(model1, model2));
        // model1 exceeded
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-max"), any()))
                .thenReturn(Optional.of(buildUsage("qwen-max", 1_500_000L)));
        // model2 within limit
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-plus"), any()))
                .thenReturn(Optional.of(buildUsage("qwen-plus", 500_000L)));

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-plus");
    }

    @Test
    void resolve_usesFirstModelWhenAllExceeded() {
        ModelRegistryEntity model1 = buildModel("qwen-max", "code", 100L, 10);
        ModelRegistryEntity model2 = buildModel("qwen-plus", "code", 100L, 20);

        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of(model1, model2));
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-max"), any()))
                .thenReturn(Optional.of(buildUsage("qwen-max", 200L)));
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-plus"), any()))
                .thenReturn(Optional.of(buildUsage("qwen-plus", 200L)));

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-max"); // best effort: highest priority
    }

    @Test
    void resolve_unlimitedModelAlwaysAvailable() {
        ModelRegistryEntity model = buildModel("qwen-turbo", "code", 0L, 10); // 0 = unlimited

        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of(model));

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-turbo");
    }

    @Test
    void resolve_fallsBackToEnvModelWhenRegisteredModelMissingConnectionConfig() {
        ModelRegistryEntity model = buildModel("db-model", "code", 0L, 10);
        model.setBaseUrl(null);
        model.setApiKeyCiphertext(null);
        when(registryRepository.findByModelRoleAndEnabledTrueOrderByPriorityAsc("code"))
                .thenReturn(List.of(model));

        String result = service.resolve("code");

        assertThat(result).isEqualTo("qwen-plus");
    }

    @Test
    void recordUsage_createsNewEntryWhenNoneExists() {
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-plus"), any()))
                .thenReturn(Optional.empty());
        when(usageDailyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordUsage("qwen-plus", 100, 50);

        ArgumentCaptor<ModelUsageDailyEntity> captor = ArgumentCaptor.forClass(ModelUsageDailyEntity.class);
        verify(usageDailyRepository).save(captor.capture());
        ModelUsageDailyEntity saved = captor.getValue();
        assertThat(saved.getModelName()).isEqualTo("qwen-plus");
        assertThat(saved.getCallCount()).isEqualTo(1L);
        assertThat(saved.getTokenInput()).isEqualTo(100L);
        assertThat(saved.getTokenOutput()).isEqualTo(50L);
        assertThat(saved.getTokenTotal()).isEqualTo(150L);
    }

    @Test
    void recordUsage_incrementsExistingEntry() {
        ModelUsageDailyEntity existing = buildUsage("qwen-plus", 1000L);
        existing.setCallCount(5L);
        existing.setTokenInput(600L);
        existing.setTokenOutput(400L);
        when(usageDailyRepository.findByModelNameAndUsageDate(eq("qwen-plus"), any()))
                .thenReturn(Optional.of(existing));
        when(usageDailyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordUsage("qwen-plus", 200, 100);

        ArgumentCaptor<ModelUsageDailyEntity> captor = ArgumentCaptor.forClass(ModelUsageDailyEntity.class);
        verify(usageDailyRepository).save(captor.capture());
        ModelUsageDailyEntity saved = captor.getValue();
        assertThat(saved.getCallCount()).isEqualTo(6L);
        assertThat(saved.getTokenInput()).isEqualTo(800L);
        assertThat(saved.getTokenOutput()).isEqualTo(500L);
        assertThat(saved.getTokenTotal()).isEqualTo(1300L);
    }

    @Test
    void dashboard_returnsAllModelsWithUsage() {
        ModelRegistryEntity model = buildModel("qwen-plus", "code", 1_000_000L, 10);
        when(registryRepository.findAllByOrderByPriorityAsc()).thenReturn(List.of(model));

        ModelUsageDailyEntity usage = buildUsage("qwen-plus", 500_000L);
        usage.setCallCount(100L);
        usage.setTokenInput(300_000L);
        usage.setTokenOutput(200_000L);
        when(usageDailyRepository.findByUsageDate(any())).thenReturn(List.of(usage));

        List<Map<String, Object>> result = service.dashboard();

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("modelName")).isEqualTo("qwen-plus");
        assertThat(item.get("todayTokenTotal")).isEqualTo(500_000L);
        assertThat(item.get("todayCallCount")).isEqualTo(100L);
        assertThat(item.get("todayRemaining")).isEqualTo(500_000L);
        assertThat(item.get("todayUsagePercent")).isEqualTo(50);
    }

    @Test
    void resolveConnection_returnsRegistryConnectionWhenPresent() {
        ModelRegistryEntity model = buildModel("qwen-plus", "code", 0L, 10);
        model.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        model.setApiKeyCiphertext("cipher-text");
        when(registryRepository.findByModelName("qwen-plus")).thenReturn(Optional.of(model));
        when(credentialCryptoService.decrypt("cipher-text")).thenReturn("sk-plain");

        Optional<ModelRouterService.ModelConnection> result = service.resolveConnection("qwen-plus");

        assertThat(result).isPresent();
        assertThat(result.get().baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(result.get().apiKey()).isEqualTo("sk-plain");
    }

    @Test
    void upsertModel_encryptsApiKeyAndPersistsMaskedFields() {
        when(registryRepository.findByModelName("qwen-plus")).thenReturn(Optional.empty());
        when(credentialCryptoService.encrypt("sk-test-1234")).thenReturn("cipher-value");
        when(credentialCryptoService.mask("sk-test-1234")).thenReturn("sk-t****1234");
        when(credentialCryptoService.version()).thenReturn("v1");
        when(registryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ModelRegistryEntity saved = service.upsertModel(
                "qwen-plus",
                "Qwen Plus",
                "dashscope",
                "code",
                1_000_000L,
                10,
                true,
                "https://dashscope.aliyuncs.com/compatible-mode",
                "sk-test-1234"
        );

        assertThat(saved.getBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode");
        assertThat(saved.getApiKeyCiphertext()).isEqualTo("cipher-value");
        assertThat(saved.getApiKeyMasked()).isEqualTo("sk-t****1234");
        assertThat(saved.getCryptoVersion()).isEqualTo("v1");
        verify(credentialCryptoService, never()).decrypt(any());
    }

    private ModelRegistryEntity buildModel(String name, String role, Long limit, int priority) {
        ModelRegistryEntity entity = new ModelRegistryEntity();
        entity.setModelName(name);
        entity.setDisplayName(name);
        entity.setProvider("dashscope");
        entity.setModelRole(role);
        entity.setDailyTokenLimit(limit);
        entity.setPriority(priority);
        entity.setEnabled(true);
        entity.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode");
        entity.setApiKeyCiphertext("cipher-" + name);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ModelUsageDailyEntity buildUsage(String modelName, Long tokenTotal) {
        ModelUsageDailyEntity entity = new ModelUsageDailyEntity();
        entity.setModelName(modelName);
        entity.setUsageDate(LocalDate.now());
        entity.setCallCount(0L);
        entity.setTokenInput(tokenTotal / 2);
        entity.setTokenOutput(tokenTotal - tokenTotal / 2);
        entity.setTokenTotal(tokenTotal);
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }
}
