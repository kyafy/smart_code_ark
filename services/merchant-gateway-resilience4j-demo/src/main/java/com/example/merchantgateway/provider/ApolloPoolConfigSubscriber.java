package com.example.merchantgateway.provider;

import com.ctrip.framework.apollo.Config;
import com.ctrip.framework.apollo.ConfigService;
import com.example.merchantgateway.config.DemoProperties;
import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.core.MerchantIsolationRegistry;
import com.example.merchantgateway.core.MerchantPoolConfigParser;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于 Apollo 的动态配置订阅器。
 *
 * <p>仅在 {@code demo.config-provider=apollo} 时生效。
 */
@Component
@ConditionalOnProperty(prefix = "demo", name = "config-provider", havingValue = "apollo")
public class ApolloPoolConfigSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ApolloPoolConfigSubscriber.class);

    private final DemoProperties demoProperties;
    private final MerchantIsolationRegistry registry;
    private final MerchantPoolConfigParser parser;

    public ApolloPoolConfigSubscriber(DemoProperties demoProperties,
                                      MerchantIsolationRegistry registry,
                                      MerchantPoolConfigParser parser) {
        this.demoProperties = demoProperties;
        this.registry = registry;
        this.parser = parser;
    }

    /**
     * 启动时拉取初始配置，并订阅 key 级别变更事件。
     */
    @PostConstruct
    public void init() {
        DemoProperties.Apollo apollo = demoProperties.getApollo();
        Config config = ConfigService.getConfig(apollo.getNamespace());

        // 首次读取 namespace/key。
        apply(config.getProperty(apollo.getKey(), ""), "apollo:init");

        config.addChangeListener(changeEvent -> {
            if (changeEvent.isChanged(apollo.getKey())) {
                String latest = config.getProperty(apollo.getKey(), "");
                // 仅目标 key 变化时才刷新，避免无效重建。
                apply(latest, "apollo:listener");
            }
        });

        log.info("apollo subscriber started, namespace={}, key={}", apollo.getNamespace(), apollo.getKey());
    }

    /** 首次拉取与监听推送共用应用逻辑。 */
    private void apply(String raw, String source) {
        try {
            MerchantPoolConfig config = parser.sanitizeNulls(parser.parse(raw));
            registry.refresh(config, source);
        } catch (Exception ex) {
            // 失败时沿用旧快照，保证服务连续可用。
            log.error("apollo apply failed, keep previous snapshot", ex);
        }
    }
}
