package com.huawei.it.ex.one.common.id.infrastructure;

import com.huawei.it.ex.one.common.instance.ApplicationInstanceIdProvider;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Locale;
import java.util.UUID;

/**
 * 默认应用实例 ID 提供者。
 *
 * <p>生产环境可以完全不配置逐实例 ID。本实现会在进程启动时生成一个只在当前进程生命周期内稳定的
 * ID；后续如需从注册中心或企业运行框架获取实例标识，可新增 {@link ApplicationInstanceIdProvider}
 * 实现并替换该 bean。</p>
 */
public class GeneratedApplicationInstanceIdProvider implements ApplicationInstanceIdProvider {
    private final String instanceId;

    public GeneratedApplicationInstanceIdProvider(String configuredInstanceId) {
        this.instanceId = configuredInstanceId == null || configuredInstanceId.isBlank()
                ? generatedInstanceId()
                : configuredInstanceId.trim();
    }

    @Override
    public String currentInstanceId() {
        return instanceId;
    }

    private String generatedInstanceId() {
        return "finex-" + sanitize(hostname()) + "-" + sanitize(pid()) + "-"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private String hostname() {
        String podName = System.getenv("POD_NAME");
        if (podName != null && !podName.isBlank()) {
            return podName;
        }
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return "unknown-host";
        }
    }

    private String pid() {
        String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
        int at = runtimeName == null ? -1 : runtimeName.indexOf('@');
        return at > 0 ? runtimeName.substring(0, at) : "unknown-pid";
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }
}
