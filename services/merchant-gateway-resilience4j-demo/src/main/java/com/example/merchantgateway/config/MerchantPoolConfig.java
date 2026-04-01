package com.example.merchantgateway.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 运行时商家流量池配置模型。
 *
 * <p>无论配置来源是本地 YAML、Nacos、Apollo，还是管理接口提交，
 * 最终都会转换为这个统一模型供隔离注册中心使用。
 */
public class MerchantPoolConfig {
    /** 配置版本号，用于审计、回滚和问题定位。 */
    private String version = "v1";
    /** 默认商家层级，映射中找不到商家时使用。 */
    private String defaultTier = "default";
    /** 各层级的容量与韧性规则，key 为层级名。 */
    private Map<String, TierRule> tiers = new HashMap<>();
    /** 商家 ID 到层级名的映射。 */
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
     * 深拷贝配置，避免多线程场景下被外部对象意外修改。
     *
     * <p>注册中心采用“不可变快照”思路，必须与调用方对象彻底隔离。
     */
    public MerchantPoolConfig copy() {
        MerchantPoolConfig copied = new MerchantPoolConfig();
        copied.setVersion(version);
        copied.setDefaultTier(defaultTier);

        Map<String, TierRule> copiedTiers = new HashMap<>();
        for (Map.Entry<String, TierRule> entry : tiers.entrySet()) {
            // 逐个复制 TierRule，避免共享引用。
            copiedTiers.put(entry.getKey(), entry.getValue().copy());
        }
        copied.setTiers(copiedTiers);
        // 字符串映射浅拷贝即可。
        copied.setMerchantTier(new HashMap<>(merchantTier));
        return copied;
    }
}
