package com.example.merchantgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Demo 的根配置对象。
 *
 * <p>配置前缀：{@code demo}
 */
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {
    /**
     * 配置来源选择：
     * local = application.yml 启动配置 + 管理接口热更新
     * nacos = 订阅 Nacos
     * apollo = 订阅 Apollo
     */
    private String configProvider = "local";
    /** 网关调用下游 mock 服务时使用的基础 URL。 */
    private String downstreamBaseUrl = "http://localhost:8088";
    /** 当 {@code demo.config-provider=nacos} 时使用的 Nacos 连接信息。 */
    private Nacos nacos = new Nacos();
    /** 当 {@code demo.config-provider=apollo} 时使用的 Apollo 连接信息。 */
    private Apollo apollo = new Apollo();

    public String getConfigProvider() {
        return configProvider;
    }

    public void setConfigProvider(String configProvider) {
        this.configProvider = configProvider;
    }

    public String getDownstreamBaseUrl() {
        return downstreamBaseUrl;
    }

    public void setDownstreamBaseUrl(String downstreamBaseUrl) {
        this.downstreamBaseUrl = downstreamBaseUrl;
    }

    public Nacos getNacos() {
        return nacos;
    }

    public void setNacos(Nacos nacos) {
        this.nacos = nacos;
    }

    public Apollo getApollo() {
        return apollo;
    }

    public void setApollo(Apollo apollo) {
        this.apollo = apollo;
    }

    public static class Nacos {
        /** Nacos 服务地址。 */
        private String serverAddr = "127.0.0.1:8848";
        /** 存储商家池配置的 DataId。 */
        private String dataId = "merchant-pool.yaml";
        /** DataId 所属 Group。 */
        private String group = "DEFAULT_GROUP";

        public String getServerAddr() {
            return serverAddr;
        }

        public void setServerAddr(String serverAddr) {
            this.serverAddr = serverAddr;
        }

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }
    }

    public static class Apollo {
        /** 存放商家池配置的 Apollo namespace。 */
        private String namespace = "merchant-pool";
        /** Apollo key，值内容为商家池配置 JSON。 */
        private String key = "merchant.pool.json";

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }
}
