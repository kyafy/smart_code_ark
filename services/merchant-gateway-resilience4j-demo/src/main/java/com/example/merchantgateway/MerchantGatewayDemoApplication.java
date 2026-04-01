package com.example.merchantgateway;

import com.example.merchantgateway.config.DemoProperties;
import com.example.merchantgateway.config.PoolBootstrapProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 示例应用启动入口。
 *
 * <p>本项目演示如何在“网关风格服务”中，为商家流量按层级隔离，并组合
 * Resilience4j 的 Bulkhead、CircuitBreaker、Retry。
 */
@SpringBootApplication
@EnableConfigurationProperties({PoolBootstrapProperties.class, DemoProperties.class})
public class MerchantGatewayDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MerchantGatewayDemoApplication.class, args);
    }

    /**
     * 暴露共享的 WebClient.Builder。
     *
     * <p>具体组件按需创建 WebClient 实例，便于做差异化调用配置。
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
