package com.example.merchantgateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 网关调试用下游 mock 服务。
 *
 * <p>支持可控延迟和失败率，方便验证重试与熔断行为。
 */
@RestController
@RequestMapping("/downstream")
public class DownstreamMockController {

    @GetMapping("/inventory/{skuId}")
    public Mono<Map<String, Object>> inventory(@PathVariable String skuId,
                                                @RequestParam(defaultValue = "0") int failPercent,
                                                @RequestParam(defaultValue = "0") int delayMs) {

        // 入参兜底，确保行为可预测。
        int boundedFail = Math.min(100, Math.max(0, failPercent));
        int boundedDelay = Math.max(0, delayMs);

        return Mono.delay(Duration.ofMillis(boundedDelay))
                .flatMap(ignore -> {
                    // 按概率制造失败，用于触发重试/熔断观测。
                    if (ThreadLocalRandom.current().nextInt(100) < boundedFail) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "mock downstream failure"
                        ));
                    }

                    // 返回模拟库存数据。
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("skuId", skuId);
                    body.put("stock", ThreadLocalRandom.current().nextInt(10, 200));
                    body.put("delayMs", boundedDelay);
                    body.put("failPercent", boundedFail);
                    body.put("service", "downstream-mock");
                    return Mono.just(body);
                });
    }
}
