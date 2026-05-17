package com.huawei.finance.front.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SuperAgent 记忆上下文配置。
 *
 * <p>ChatService 的记忆能力是可选增强，不是聊天主链路强依赖。短期记忆负责装配最近几轮问答，
 * 长期记忆负责调用可替换的外部长记忆服务；两者均默认关闭，避免正式首版在未明确启用时产生额外
 * Redis、openGauss 或外部服务访问。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.memory")
public class MemoryProperties {
    /** 短期最近问答上下文配置。 */
    private final ShortTerm shortTerm = new ShortTerm();
    /** 长期记忆检索配置。 */
    private final LongTerm longTerm = new LongTerm();

    public ShortTerm getShortTerm() {
        return shortTerm;
    }

    public LongTerm getLongTerm() {
        return longTerm;
    }

    /**
     * 判断是否需要为本轮 run 装配任何记忆上下文。
     *
     * @return true 表示至少启用了短期或长期记忆。
     */
    public boolean contextEnabled() {
        return shortTerm.enabled || longTerm.enabled;
    }

    /**
     * 短期最近问答上下文配置。
     */
    public static class ShortTerm {
        /** 是否启用短期最近问答上下文装配，默认关闭。 */
        private boolean enabled = false;
        /** 最近问答轮次数；一轮通常包含一条 user 消息和一条 assistant 消息。 */
        private int recentTurns = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRecentTurns() {
            return recentTurns;
        }

        public void setRecentTurns(int recentTurns) {
            this.recentTurns = recentTurns;
        }

        /**
         * 将最近轮次转换为最近消息条数。
         *
         * @return 至少 2 条消息，避免配置为 0 时产生无意义查询。
         */
        public int recentMessageLimit() {
            return Math.max(1, recentTurns) * 2;
        }
    }

    /**
     * 长期记忆检索配置。
     */
    public static class LongTerm {
        /** 是否启用长期记忆检索，默认关闭。 */
        private boolean enabled = false;
        /** 长期记忆 provider 名称，默认 disabled，具体 adapter 由基础设施层按 provider 装配。 */
        private String provider = "disabled";
        /** 每轮最多检索的长期记忆条数。 */
        private int topK = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        /**
         * @return 规范化后的检索条数，至少为 1。
         */
        public int normalizedTopK() {
            return Math.max(1, topK);
        }
    }
}
