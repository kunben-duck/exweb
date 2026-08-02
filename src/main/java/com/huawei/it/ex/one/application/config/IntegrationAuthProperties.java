package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 外部集成服务调用鉴权配置。
 */
@Component
@ConfigurationProperties(prefix = "financeex.integration-auth")
public class IntegrationAuthProperties {
    /** 是否启用集成服务鉴权请求头注入。 */
    private boolean enabled = false;
    /** 未配置具体服务时使用的默认鉴权 provider。 */
    private String defaultProvider = "none";
    /** Sgov 鉴权配置。 */
    private Sgov sgov = new Sgov();
    /** 服务编码到鉴权 provider 的映射。 */
    private Map<String, Service> services = defaultServices();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultProvider() {
        return defaultProvider;
    }

    public void setDefaultProvider(String defaultProvider) {
        this.defaultProvider = defaultProvider;
    }

    public Sgov getSgov() {
        return sgov;
    }

    public void setSgov(Sgov sgov) {
        this.sgov = sgov == null ? new Sgov() : sgov;
    }

    public Map<String, Service> getServices() {
        return services;
    }

    public void setServices(Map<String, Service> services) {
        if (services == null || services.isEmpty()) {
            this.services = defaultServices();
            return;
        }
        Map<String, Service> normalized = new LinkedHashMap<>();
        services.forEach((serviceCode, service) -> normalized.put(normalize(serviceCode),
                service == null ? service("none") : service));
        this.services = normalized;
    }

    public String providerFor(String serviceCode) {
        if (!enabled) {
            return "none";
        }
        String normalizedService = normalize(serviceCode);
        Service service = services.get(normalizedService);
        String provider = service == null ? defaultProvider : service.getProvider();
        String normalizedProvider = normalize(provider);
        return normalizedProvider.isBlank() ? "none" : normalizedProvider;
    }

    private static Map<String, Service> defaultServices() {
        Map<String, Service> defaults = new LinkedHashMap<>();
        defaults.put("welink-share", service("sgov"));
        defaults.put("intent-service", service("sgov"));
        defaults.put("session-title", service("sgov"));
        defaults.put("use-case-library", service("sgov"));
        return defaults;
    }

    private static Service service(String provider) {
        Service service = new Service();
        service.setProvider(provider);
        return service;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Sgov 鉴权配置。
     */
    public static class Sgov {
        /** 服务 ID。 */
        private String appId = "";
        /** 服务密钥。 */
        private String secret = "";

        public String getAppId() {
            return appId;
        }

        public void setAppId(String appId) {
            this.appId = appId;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /**
     * 单个出站服务的鉴权 provider 配置。
     */
    public static class Service {
        /** 鉴权 provider 编码，例如 none、sgov。 */
        private String provider = "none";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }
}
