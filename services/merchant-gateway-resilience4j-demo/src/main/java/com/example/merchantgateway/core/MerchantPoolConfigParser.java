package com.example.merchantgateway.core;

import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.config.TierRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;

/**
 * 动态配置解析与序列化工具。
 *
 * <p>同时支持 JSON 与 YAML，便于 Nacos、Apollo、管理接口共用一套解析流程。
 */
@Component
public class MerchantPoolConfigParser {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    /**
     * 将原始文本解析为配置对象（JSON 或 YAML）。
     *
     * <p>根据首字符自动判断格式。
     */
    public MerchantPoolConfig parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("raw config is empty");
        }

        String trimmed = raw.trim();
        try {
            // JSON 通常以 { 或 [ 开头。
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return jsonMapper.readValue(trimmed, MerchantPoolConfig.class);
            }
            // 其余情况按 YAML 处理。
            return yamlMapper.readValue(trimmed, MerchantPoolConfig.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to parse config as JSON/YAML", ex);
        }
    }

    /** 将配置输出为格式化 JSON，便于在管理接口中查看。 */
    public String toPrettyJson(MerchantPoolConfig config) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize config", ex);
        }
    }

    /**
     * 在严格校验前做轻量“空值归一化”。
     *
     * <p>该方法不保证配置正确性，最终仍需走 Validator。
     */
    public MerchantPoolConfig sanitizeNulls(MerchantPoolConfig config) {
        if (config.getTiers() == null) {
            config.setTiers(new java.util.HashMap<>());
        }
        if (config.getMerchantTier() == null) {
            config.setMerchantTier(new java.util.HashMap<>());
        }
        if (config.getVersion() == null || config.getVersion().isBlank()) {
            config.setVersion("unknown");
        }
        if (config.getDefaultTier() == null || config.getDefaultTier().isBlank()) {
            config.setDefaultTier("default");
        }
        // tier 规则为 null 时无法构建韧性组件，直接视为非法。
        for (TierRule rule : config.getTiers().values()) {
            if (rule == null) {
                throw new IllegalArgumentException("tier rule must not be null");
            }
        }
        return config;
    }
}
