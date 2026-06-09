package com.huawei.finance.front.one.infrastructure.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 跨实例实时事件总线配置。
 */
@ConfigurationProperties(prefix = "financeex.websocket")
public class ChatLiveEventBusProperties {
    /** Redis Pub/Sub 逻辑 channel 前缀，必须以 fin_ex 开头；运行时会自动插入环境段。 */
    private String redisChannelPrefix = "fin_ex:chat_stream";

    public String getRedisChannelPrefix() {
        return redisChannelPrefix;
    }

    public void setRedisChannelPrefix(String redisChannelPrefix) {
        this.redisChannelPrefix = redisChannelPrefix;
    }
}
