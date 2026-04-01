package com.example.merchantgateway.provider;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.AbstractListener;
import com.example.merchantgateway.config.DemoProperties;
import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.core.MerchantIsolationRegistry;
import com.example.merchantgateway.core.MerchantPoolConfigParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Properties;

/**
 * 基于 Nacos 的动态配置订阅器。
 *
 * <p>仅在 {@code demo.config-provider=nacos} 时生效。
 */
@Component
@ConditionalOnProperty(prefix = "demo", name = "config-provider", havingValue = "nacos")
public class NacosPoolConfigSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NacosPoolConfigSubscriber.class);

    private final DemoProperties demoProperties;
    private final MerchantIsolationRegistry registry;
    private final MerchantPoolConfigParser parser;

    public NacosPoolConfigSubscriber(DemoProperties demoProperties,
                                     MerchantIsolationRegistry registry,
                                     MerchantPoolConfigParser parser) {
        this.demoProperties = demoProperties;
        this.registry = registry;
        this.parser = parser;
    }

    /**
     * 启动时拉取初始配置，并注册监听器接收后续变更。
     *
     * <p>如果解析或校验失败，会保留上一个可用快照。
     */
    @PostConstruct
    public void init() throws Exception {
        DemoProperties.Nacos nacos = demoProperties.getNacos();
        Properties properties = new Properties();
        properties.put("serverAddr", nacos.getServerAddr());

        ConfigService configService = NacosFactory.createConfigService(properties);
        String raw = configService.getConfig(nacos.getDataId(), nacos.getGroup(), 3000);
        // 首次拉取，确保服务对外前已有可用配置。
        apply(raw, "nacos:init");

        configService.addListener(nacos.getDataId(), nacos.getGroup(), new AbstractListener() {
            @Override
            public void receiveConfigInfo(String configInfo) {
                // Nacos 推送增量变更。
                apply(configInfo, "nacos:listener");
            }
        });

        log.info("nacos subscriber started, dataId={}, group={}", nacos.getDataId(), nacos.getGroup());
    }

    /** 首次拉取与监听推送共用的配置应用逻辑。 */
    private void apply(String raw, String source) {
        try {
            MerchantPoolConfig config = parser.sanitizeNulls(parser.parse(raw));
            registry.refresh(config, source);
        } catch (Exception ex) {
            // 失败时保留上一次可用快照，避免影响在线请求。
            log.error("nacos apply failed, keep previous snapshot", ex);
        }
    }
}
