package com.example.merchantgateway;

import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.config.TierRule;
import com.example.merchantgateway.core.MerchantIsolationRegistry;
import com.example.merchantgateway.core.MerchantPoolConfigValidator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 注册中心“刷新 + tier 解析”核心行为单测。
 */
class MerchantIsolationRegistryTest {

    @Test
    void shouldResolveMerchantTierAfterRefresh() {
        MerchantIsolationRegistry registry = new MerchantIsolationRegistry(new MerchantPoolConfigValidator());

        MerchantPoolConfig config = new MerchantPoolConfig();
        config.setVersion("test-v1");
        config.setDefaultTier("default");

        Map<String, TierRule> tiers = new HashMap<>();
        tiers.put("default", tierRule(5));
        tiers.put("vip", tierRule(20));
        config.setTiers(tiers);

        Map<String, String> merchantTier = new HashMap<>();
        merchantTier.put("m1001", "vip");
        config.setMerchantTier(merchantTier);

        // refresh 会一次性发布完整快照。
        registry.refresh(config, "test");

        assertEquals("vip", registry.resolveTier("m1001"));
        // 未映射商家应回退到 default tier。
        assertEquals("default", registry.resolveTier("unknown"));
    }

    /** 构造一份最小可用的 tier 规则。 */
    private TierRule tierRule(int concurrent) {
        TierRule rule = new TierRule();
        rule.setMaxConcurrentCalls(concurrent);
        rule.setMaxWaitDurationMs(0);
        rule.setSlidingWindowSize(10);
        rule.setMinimumNumberOfCalls(5);
        rule.setFailureRateThreshold(50f);
        rule.setOpenStateWaitMs(1000);
        rule.setHalfOpenCalls(2);
        rule.setRetryMaxAttempts(1);
        rule.setRetryWaitMs(10);
        return rule;
    }
}
