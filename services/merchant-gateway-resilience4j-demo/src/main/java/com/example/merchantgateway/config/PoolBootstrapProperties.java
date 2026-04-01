package com.example.merchantgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 启动阶段的基础配置对象，读取自 {@code demo.pool.*}。
 *
 * <p>在 Nacos/Apollo 推送动态配置前，系统先用这份配置构建初始快照。
 */
@ConfigurationProperties(prefix = "demo.pool")
public class PoolBootstrapProperties {
    private String version = "v1";
    private String defaultTier = "default";
    private Map<String, TierRule> tiers = new HashMap<>();
    private Map<String, String> merchantTier = new HashMap<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDefaultTier() {
        return defaultTier;
    }

    public void setDefaultTier(String defaultTier) {
        this.defaultTier = defaultTier;
    }

    public Map<String, TierRule> getTiers() {
        return tiers;
    }

    public void setTiers(Map<String, TierRule> tiers) {
        this.tiers = tiers;
    }

    public Map<String, String> getMerchantTier() {
        return merchantTier;
    }

    public void setMerchantTier(Map<String, String> merchantTier) {
        this.merchantTier = merchantTier;
    }

    /**
     * 将 Spring 绑定配置转换为运行时模型。
     *
     * <p>这里同样使用深拷贝，确保运行态快照不会被配置对象后续修改影响。
     */
    public MerchantPoolConfig toConfig() {
        MerchantPoolConfig config = new MerchantPoolConfig();
        config.setVersion(version);
        config.setDefaultTier(defaultTier);

        Map<String, TierRule> copiedTiers = new HashMap<>();
        for (Map.Entry<String, TierRule> entry : tiers.entrySet()) {
            // 防御性复制，避免属性对象共享引用。
            copiedTiers.put(entry.getKey(), entry.getValue().copy());
        }
        config.setTiers(copiedTiers);
        config.setMerchantTier(new HashMap<>(merchantTier));
        return config;
    }
}
