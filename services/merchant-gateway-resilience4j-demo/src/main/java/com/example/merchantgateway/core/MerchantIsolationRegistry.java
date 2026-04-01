package com.example.merchantgateway.core;

import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.config.TierRule;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 商家隔离运行态注册中心。
 *
 * <p>设计目标：
 * 1) 请求路径读取无锁、足够快
 * 2) 配置更新时整体快照原子替换
 * 3) 刷新过程中不暴露半成品状态
 */
@Service
public class MerchantIsolationRegistry {

    /** 请求执行时使用的韧性组件组合。 */
    public record IsolationSet(Bulkhead bulkhead, CircuitBreaker circuitBreaker, Retry retry) {}
    /** 不可变运行态快照。 */
    private record Snapshot(MerchantPoolConfig config, Map<String, IsolationSet> tierSets) {}

    private static final Logger log = LoggerFactory.getLogger(MerchantIsolationRegistry.class);

    private final MerchantPoolConfigValidator validator;
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>(
            new Snapshot(new MerchantPoolConfig(), Map.of())
    );

    public MerchantIsolationRegistry(MerchantPoolConfigValidator validator) {
        this.validator = validator;
    }

    /**
     * 解析某商家对应的韧性组件集合。
     *
     * <p>若商家没有配置映射，自动回退到默认 tier。
     */
    public IsolationSet resolveSet(String merchantId) {
        Snapshot snapshot = snapshotRef.get();
        String tier = resolveTier(merchantId);
        IsolationSet set = snapshot.tierSets().get(tier);
        if (set != null) {
            return set;
        }
        IsolationSet defaultSet = snapshot.tierSets().get(snapshot.config().getDefaultTier());
        if (defaultSet == null) {
            throw new IllegalStateException("default tier set not found");
        }
        return defaultSet;
    }

    /** 基于当前快照解析商家 tier。 */
    public String resolveTier(String merchantId) {
        Snapshot snapshot = snapshotRef.get();
        if (merchantId == null || merchantId.isBlank()) {
            return snapshot.config().getDefaultTier();
        }
        return snapshot.config().getMerchantTier().getOrDefault(merchantId, snapshot.config().getDefaultTier());
    }

    /** 返回当前配置副本，供管理接口读取。 */
    public MerchantPoolConfig currentConfig() {
        return snapshotRef.get().config().copy();
    }

    /**
     * 原子替换整份运行态快照。
     *
     * <p>使用 synchronized 防止并发刷新互相覆盖；请求路径只读 AtomicReference，不受锁影响。
     */
    public synchronized void refresh(MerchantPoolConfig nextConfig, String source) {
        // 先复制再校验，避免修改调用方传入对象。
        MerchantPoolConfig config = nextConfig.copy();
        validator.validate(config);

        Map<String, IsolationSet> nextSets = new HashMap<>();
        for (Map.Entry<String, TierRule> entry : config.getTiers().entrySet()) {
            nextSets.put(entry.getKey(), buildIsolation(entry.getKey(), entry.getValue()));
        }

        // 唯一发布点：要么看到旧快照，要么看到完整新快照。
        snapshotRef.set(new Snapshot(config, Map.copyOf(nextSets)));
        log.info("merchant pool config applied: source={}, version={}, tiers={}", source, config.getVersion(), nextSets.keySet());
    }

    /** 根据 tier 规则构建 Bulkhead/CircuitBreaker/Retry。 */
    private IsolationSet buildIsolation(String tier, TierRule rule) {
        Bulkhead bulkhead = Bulkhead.of(
                "bh-" + tier,
                BulkheadConfig.custom()
                        // 负数归零，避免配置脏值导致异常。
                        .maxConcurrentCalls(rule.getMaxConcurrentCalls())
                        .maxWaitDuration(Duration.ofMillis(Math.max(0, rule.getMaxWaitDurationMs())))
                        .build()
        );

        CircuitBreaker circuitBreaker = CircuitBreaker.of(
                "cb-" + tier,
                CircuitBreakerConfig.custom()
                        // Demo 使用按次数窗口，行为更可预测、便于观察。
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(rule.getSlidingWindowSize())
                        .minimumNumberOfCalls(rule.getMinimumNumberOfCalls())
                        .failureRateThreshold(rule.getFailureRateThreshold())
                        .waitDurationInOpenState(Duration.ofMillis(Math.max(0, rule.getOpenStateWaitMs())))
                        .permittedNumberOfCallsInHalfOpenState(rule.getHalfOpenCalls())
                        .build()
        );

        Retry retry = Retry.of(
                "rt-" + tier,
                RetryConfig.custom()
                        .maxAttempts(rule.getRetryMaxAttempts())
                        .waitDuration(Duration.ofMillis(Math.max(0, rule.getRetryWaitMs())))
                        // Demo 对 Exception 全量重试；生产建议精细化异常名单。
                        .retryExceptions(Exception.class)
                        .build()
        );

        return new IsolationSet(bulkhead, circuitBreaker, retry);
    }
}
