package com.smartark.gateway.service.codegen;

import com.smartark.gateway.agent.AgentExecutionContext;
import com.smartark.gateway.service.TemplateRepoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class InternalCodegenService {
    private final Map<String, CodegenProvider> providers;

    @Value("${smartark.codegen.internal.provider-order:jeecg}")
    private String providerOrder;

    @Value("${smartark.codegen.internal.hybrid-provider-order:jeecg}")
    private String hybridProviderOrder;

    @Value("${smartark.codegen.internal.strict-provider-order:jeecg}")
    private String strictProviderOrder;

    public InternalCodegenService(List<CodegenProvider> providerList) {
        Map<String, CodegenProvider> providerMap = new LinkedHashMap<>();
        for (CodegenProvider provider : providerList) {
            if (provider == null || provider.providerKey() == null || provider.providerKey().isBlank()) {
                continue;
            }
            providerMap.put(provider.providerKey().trim().toLowerCase(Locale.ROOT), provider);
        }
        this.providers = Map.copyOf(providerMap);
    }

    public CodegenRenderResult tryRender(
            String codegenEngine,
            AgentExecutionContext context,
            TemplateRepoService.TemplateSelection selection
    ) {
        String normalizedEngine = normalizeCodegenEngine(codegenEngine);
        if ("llm".equals(normalizedEngine)) {
            return CodegenRenderResult.notInvoked("internal", "llm mode skips internal codegen");
        }
        if (selection == null) {
            return CodegenRenderResult.notInvoked("internal", "template selection is required for internal codegen");
        }
        List<String> providerKeys = resolveProviderOrder(normalizedEngine);
        if (providerKeys.isEmpty()) {
            return CodegenRenderResult.failed("internal", "no internal codegen provider configured");
        }

        CodegenRenderResult lastAttempt = CodegenRenderResult.notInvoked("internal", "no provider was executed");
        for (String providerKey : providerKeys) {
            CodegenProvider provider = providers.get(providerKey);
            if (provider == null) {
                lastAttempt = CodegenRenderResult.notInvoked("internal", "provider not found: " + providerKey);
                continue;
            }
            CodegenRenderResult result = provider.tryRender(context, selection, normalizedEngine);
            if (result == null) {
                lastAttempt = CodegenRenderResult.failed(providerKey, "provider returned null result");
                continue;
            }
            lastAttempt = result;
            if (result.success()) {
                return result;
            }
        }
        return lastAttempt;
    }

    private List<String> resolveProviderOrder(String codegenEngine) {
        String normalizedEngine = normalizeCodegenEngine(codegenEngine);
        if ("jeecg_rule".equals(normalizedEngine)) {
            // jeecg_rule is expected to always hit Jeecg render path first
            // so sidecar/upstream diagnostics are observable and deterministic.
            return parseProviderOrder("jeecg");
        }
        String rawOrder = switch (normalizedEngine) {
            case "hybrid" -> hybridProviderOrder;
            case "internal_service" -> strictProviderOrder;
            default -> providerOrder;
        };
        return parseProviderOrder(rawOrder);
    }

    private List<String> parseProviderOrder(String rawOrder) {
        String source = rawOrder == null ? "" : rawOrder;
        Set<String> order = new LinkedHashSet<>();
        for (String item : source.split(",")) {
            String key = item == null ? "" : item.trim().toLowerCase(Locale.ROOT);
            if (!key.isBlank()) {
                order.add(key);
            }
        }
        if (order.isEmpty()) {
            order.add("jeecg");
        }
        return new ArrayList<>(order);
    }

    private String normalizeCodegenEngine(String value) {
        if (value == null || value.isBlank()) {
            return "llm";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "llm", "jeecg_rule", "hybrid", "internal_service" -> normalized;
            default -> "llm";
        };
    }
}
