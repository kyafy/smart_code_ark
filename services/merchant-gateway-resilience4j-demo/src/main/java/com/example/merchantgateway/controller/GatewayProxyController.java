package com.example.merchantgateway.controller;

import com.example.merchantgateway.config.DemoProperties;
import com.example.merchantgateway.core.MerchantIsolationRegistry;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关风格代理接口：将请求转发到下游 mock 服务。
 *
 * <p>调用链会按商家 tier 装配对应的 Bulkhead、CircuitBreaker、Retry。
 */
@RestController
@RequestMapping("/gateway")
public class GatewayProxyController {

    private final MerchantIsolationRegistry registry;
    private final DemoProperties demoProperties;
    private final WebClient webClient;

    public GatewayProxyController(MerchantIsolationRegistry registry,
                                  DemoProperties demoProperties,
                                  WebClient.Builder webClientBuilder) {
        this.registry = registry;
        this.demoProperties = demoProperties;
        this.webClient = webClientBuilder.build();
    }

    @GetMapping("/inventory/{skuId}")
    public Mono<ResponseEntity<Map<String, Object>>> proxyInventory(
            @PathVariable String skuId,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantId,
            @RequestParam(defaultValue = "0") int failPercent,
            @RequestParam(defaultValue = "0") int delayMs) {

        // 未传商家头时走匿名商家，最终会命中默认 tier。
        String merchant = merchantId == null || merchantId.isBlank() ? "anonymous" : merchantId;
        String tier = registry.resolveTier(merchant);
        MerchantIsolationRegistry.IsolationSet set = registry.resolveSet(merchant);

        // Demo 为方便本地运行，将下游接口放在同一进程。
        String url = String.format("%s/downstream/inventory/%s?failPercent=%d&delayMs=%d",
                demoProperties.getDownstreamBaseUrl(), skuId, Math.max(0, failPercent), Math.max(0, delayMs));

        Mono<Map<String, Object>> invokeDownstream = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});

        return invokeDownstream
                // 顺序有意设计：先限并发隔离，再熔断判定，再按策略重试。
                .transformDeferred(BulkheadOperator.of(set.bulkhead()))
                .transformDeferred(CircuitBreakerOperator.of(set.circuitBreaker()))
                .transformDeferred(RetryOperator.of(set.retry()))
                .map(body -> ResponseEntity.ok(successBody(merchant, tier, body)))
                // Bulkhead 拒绝说明当前 tier 流量池已满。
                .onErrorResume(BulkheadFullException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .body(errorBody("bulkhead_full", merchant, tier, ex.getMessage()))))
                // 熔断打开：近期失败率超过阈值，快速失败。
                .onErrorResume(CallNotPermittedException.class,
                        ex -> Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(errorBody("circuit_open", merchant, tier, ex.getMessage()))))
                // 兜底响应：保障调试场景下总能拿到结构化结果。
                .onErrorResume(ex -> Mono.just(ResponseEntity.ok(
                        errorBody("fallback", merchant, tier, ex.getMessage()))));
    }

    /** 统一成功返回体。 */
    private Map<String, Object> successBody(String merchantId, String tier, Map<String, Object> downstream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("merchantId", merchantId);
        body.put("tier", tier);
        body.put("downstream", downstream);
        return body;
    }

    /** 统一失败返回体。 */
    private Map<String, Object> errorBody(String type, String merchantId, String tier, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("type", type);
        body.put("merchantId", merchantId);
        body.put("tier", tier);
        body.put("message", message);
        return body;
    }
}
