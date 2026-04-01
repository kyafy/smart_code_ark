package com.example.merchantgateway.core;

import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.config.TierRule;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 商家流量池配置校验器。
 *
 * <p>所有动态配置在进入运行态快照前都必须通过这里的语义校验。
 */
@Component
public class MerchantPoolConfigValidator {

    /**
     * 校验整份配置的一致性与边界。
     *
     * <p>遇到首个非法条件时抛出 {@link IllegalArgumentException}。
     */
    public void validate(MerchantPoolConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is null");
        }
        if (isBlank(config.getDefaultTier())) {
            throw new IllegalArgumentException("defaultTier is blank");
        }
        if (config.getTiers() == null || config.getTiers().isEmpty()) {
            throw new IllegalArgumentException("tiers is empty");
        }
        if (!config.getTiers().containsKey(config.getDefaultTier())) {
            throw new IllegalArgumentException("defaultTier not found in tiers");
        }

        // 逐个 tier 校验，任何一个 tier 非法都阻断整次发布。
        for (Map.Entry<String, TierRule> entry : config.getTiers().entrySet()) {
            String tier = entry.getKey();
            TierRule rule = entry.getValue();
            if (rule == null) {
                throw new IllegalArgumentException("tier rule is null: " + tier);
            }
            if (rule.getMaxConcurrentCalls() <= 0) {
                throw new IllegalArgumentException("maxConcurrentCalls must be > 0 for tier=" + tier);
            }
            if (rule.getSlidingWindowSize() <= 0) {
                throw new IllegalArgumentException("slidingWindowSize must be > 0 for tier=" + tier);
            }
            if (rule.getMinimumNumberOfCalls() <= 0) {
                throw new IllegalArgumentException("minimumNumberOfCalls must be > 0 for tier=" + tier);
            }
            if (rule.getMinimumNumberOfCalls() > rule.getSlidingWindowSize()) {
                throw new IllegalArgumentException("minimumNumberOfCalls must be <= slidingWindowSize for tier=" + tier);
            }
            if (rule.getFailureRateThreshold() <= 0 || rule.getFailureRateThreshold() > 100) {
                throw new IllegalArgumentException("failureRateThreshold must be (0,100] for tier=" + tier);
            }
            if (rule.getRetryMaxAttempts() <= 0) {
                throw new IllegalArgumentException("retryMaxAttempts must be > 0 for tier=" + tier);
            }
        }
    }

    /** 本地字符串判空辅助方法。 */
    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
