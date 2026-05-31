package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MVC/Servlet 模式下 SSE 连接治理配置。
 *
 * <p>SSE 在 MVC 下依赖 Servlet async 长连接。该配置只影响服务端恢复流的心跳节奏；
 * 容器级 async timeout 和 Tomcat 连接数仍通过 Spring Boot 原生 {@code spring.mvc}
 * 与 {@code server.tomcat} 配置控制。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.mvc.sse")
public class MvcSseProperties {
    /** run 级 Event Resume 无业务事件时发送 heartbeat 的间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * @return 规范化后的 heartbeat 间隔；小于等于 0 时由调用方视为关闭 heartbeat。
     */
    public Duration normalizedHeartbeatInterval() {
        return heartbeatInterval == null ? Duration.ZERO : heartbeatInterval;
    }
}
