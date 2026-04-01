package com.example.merchantgateway.controller;

import com.example.merchantgateway.config.MerchantPoolConfig;
import com.example.merchantgateway.core.MerchantIsolationRegistry;
import com.example.merchantgateway.core.MerchantPoolConfigParser;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理接口：查看和热更新商家流量池配置。
 */
@RestController
@RequestMapping("/admin")
public class AdminConfigController {

    private final MerchantIsolationRegistry registry;
    private final MerchantPoolConfigParser parser;

    public AdminConfigController(MerchantIsolationRegistry registry, MerchantPoolConfigParser parser) {
        this.registry = registry;
        this.parser = parser;
    }

    /** 查询当前生效配置快照。 */
    @GetMapping("/pool-config")
    public MerchantPoolConfig currentConfig() {
        return registry.currentConfig();
    }

    /** 以结构化 JSON 对象方式更新配置。 */
    @PutMapping(value = "/pool-config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateConfig(@RequestBody MerchantPoolConfig newConfig) {
        MerchantPoolConfig sanitized = parser.sanitizeNulls(newConfig);
        registry.refresh(sanitized, "admin-json");
        return result("applied", sanitized.getVersion());
    }

    /** 以原始文本方式更新（支持 YAML/JSON）。 */
    @PutMapping(value = "/pool-config/raw", consumes = MediaType.TEXT_PLAIN_VALUE)
    public Map<String, Object> updateRaw(@RequestBody String rawConfig) {
        MerchantPoolConfig parsed = parser.sanitizeNulls(parser.parse(rawConfig));
        registry.refresh(parsed, "admin-raw");
        return result("applied", parsed.getVersion());
    }

    /** 查询指定商家的实际 tier。 */
    @GetMapping("/tier/{merchantId}")
    public Map<String, Object> tier(@PathVariable String merchantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantId", merchantId);
        body.put("tier", registry.resolveTier(merchantId));
        return body;
    }

    /** 以美化 JSON 形式输出当前配置，便于复制与排查。 */
    @GetMapping("/pool-config/pretty")
    public String prettyJson() {
        return parser.toPrettyJson(registry.currentConfig());
    }

    /** 统一管理接口的简要返回结构。 */
    private Map<String, Object> result(String status, String version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("version", version);
        return body;
    }
}
