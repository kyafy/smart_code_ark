package com.example.merchantgateway.core;

import com.example.merchantgateway.config.PoolBootstrapProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动完成后加载本地初始配置。
 *
 * <p>后续如果开启 Nacos/Apollo，会在此基础上继续覆盖更新。
 */
@Component
public class PoolBootstrapLoader {

    private final PoolBootstrapProperties bootstrapProperties;
    private final MerchantIsolationRegistry registry;

    public PoolBootstrapLoader(PoolBootstrapProperties bootstrapProperties, MerchantIsolationRegistry registry) {
        this.bootstrapProperties = bootstrapProperties;
        this.registry = registry;
    }

    /** 在容器就绪后写入第一份可用快照。 */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        registry.refresh(bootstrapProperties.toConfig(), "bootstrap");
    }
}
