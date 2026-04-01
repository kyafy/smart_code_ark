package com.example.merchantgateway.config;

/**
 * 单个商家层级（tier）的韧性参数。
 *
 * <p>每个 tier 都会基于这组参数构建一套 Bulkhead、CircuitBreaker、Retry。
 */
public class TierRule {
    /** 信号量隔离允许的最大并发数。 */
    private int maxConcurrentCalls = 50;
    /** 达到并发上限后等待时长，超时则拒绝。 */
    private long maxWaitDurationMs = 0;

    /** 熔断统计窗口大小（最近调用次数）。 */
    private int slidingWindowSize = 20;
    /** 达到该调用次数后才开始计算失败率。 */
    private int minimumNumberOfCalls = 10;
    /** 失败率阈值（百分比），达到后进入 OPEN。 */
    private float failureRateThreshold = 50f;
    /** 熔断器在 OPEN 状态维持时长。 */
    private long openStateWaitMs = 10000;
    /** HALF_OPEN 状态允许的探测请求数。 */
    private int halfOpenCalls = 3;

    /** 最大尝试次数（包含首次调用，不只是重试次数）。 */
    private int retryMaxAttempts = 2;
    /** 每次重试之间的等待时长。 */
    private long retryWaitMs = 100;

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public long getMaxWaitDurationMs() {
        return maxWaitDurationMs;
    }

    public void setMaxWaitDurationMs(long maxWaitDurationMs) {
        this.maxWaitDurationMs = maxWaitDurationMs;
    }

    public int getSlidingWindowSize() {
        return slidingWindowSize;
    }

    public void setSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
    }

    public int getMinimumNumberOfCalls() {
        return minimumNumberOfCalls;
    }

    public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
        this.minimumNumberOfCalls = minimumNumberOfCalls;
    }

    public float getFailureRateThreshold() {
        return failureRateThreshold;
    }

    public void setFailureRateThreshold(float failureRateThreshold) {
        this.failureRateThreshold = failureRateThreshold;
    }

    public long getOpenStateWaitMs() {
        return openStateWaitMs;
    }

    public void setOpenStateWaitMs(long openStateWaitMs) {
        this.openStateWaitMs = openStateWaitMs;
    }

    public int getHalfOpenCalls() {
        return halfOpenCalls;
    }

    public void setHalfOpenCalls(int halfOpenCalls) {
        this.halfOpenCalls = halfOpenCalls;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryWaitMs() {
        return retryWaitMs;
    }

    public void setRetryWaitMs(long retryWaitMs) {
        this.retryWaitMs = retryWaitMs;
    }

    /**
     * 返回当前规则的复制对象。
     *
     * <p>用于构建不可变快照，避免引用共享。
     */
    public TierRule copy() {
        TierRule copied = new TierRule();
        copied.setMaxConcurrentCalls(maxConcurrentCalls);
        copied.setMaxWaitDurationMs(maxWaitDurationMs);
        copied.setSlidingWindowSize(slidingWindowSize);
        copied.setMinimumNumberOfCalls(minimumNumberOfCalls);
        copied.setFailureRateThreshold(failureRateThreshold);
        copied.setOpenStateWaitMs(openStateWaitMs);
        copied.setHalfOpenCalls(halfOpenCalls);
        copied.setRetryMaxAttempts(retryMaxAttempts);
        copied.setRetryWaitMs(retryWaitMs);
        return copied;
    }
}
