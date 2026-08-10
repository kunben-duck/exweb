package com.huawei.it.ex.one.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.infrastructure.memory.ShortTermMemoryRedisProperties;
import com.huawei.it.ex.one.infrastructure.persistence.ChatLiveEventBusProperties;
import com.huawei.it.ex.one.infrastructure.persistence.ChatRunCacheProperties;
import com.huawei.it.ex.one.infrastructure.runtime.RuntimeBindingProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FinanceExRedisKeyBuilderTest {

    @Test
    void usesFirstActiveProfileAsEnvironmentSegment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev", "blue");

        FinanceExRedisKeyBuilder redisKeys = new FinanceExRedisKeyBuilder(environment,
                new RuntimeBindingProperties(), new ChatRunCacheProperties(),
                new ChatLiveEventBusProperties(), new ShortTermMemoryRedisProperties());

        assertThat(redisKeys.env()).isEqualTo("dev");
        assertThat(redisKeys.activeRun("tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:chat_run:active:tenant1:user1:session1");
    }

    @Test
    void usesDefaultEnvironmentWhenNoActiveProfileExists() {
        FinanceExRedisKeyBuilder redisKeys = new FinanceExRedisKeyBuilder(new MockEnvironment(),
                new RuntimeBindingProperties(), new ChatRunCacheProperties(),
                new ChatLiveEventBusProperties(), new ShortTermMemoryRedisProperties());

        assertThat(redisKeys.env()).isEqualTo("default");
        assertThat(redisKeys.chatStreamChannel("chat-run-run1"))
                .isEqualTo("fin_ex:default:chat_stream:chat-run-run1");
    }

    @Test
    void normalizesUnsafeEnvironmentCharacters() {
        assertThat(FinanceExRedisKeyBuilder.ofEnv("Prod Blue").env()).isEqualTo("prod_blue");
        assertThat(FinanceExRedisKeyBuilder.ofEnv("prod:blue").env()).isEqualTo("prod_blue");
        assertThat(FinanceExRedisKeyBuilder.ofEnv(" ! ").env()).isEqualTo("default");
    }

    @Test
    void buildsAllRedisKeysWithEnvironmentSegment() {
        FinanceExRedisKeyBuilder redisKeys = FinanceExRedisKeyBuilder.ofEnv("dev");

        assertThat(redisKeys.runtimeBinding("tenant1", "user1", "session1", "leaf1"))
                .isEqualTo("fin_ex:dev:runtime_binding:{tenant1:user1:session1}:leaf1");
        assertThat(redisKeys.runtimeBindingIndex("tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:runtime_binding:index:{tenant1:user1:session1}");
        assertThat(redisKeys.cancelFlag("run1")).isEqualTo("fin_ex:dev:chat_run:cancel:run1");
        assertThat(redisKeys.recoverLock("run1")).isEqualTo("fin_ex:dev:chat_run:recover_lock:run1");
        assertThat(redisKeys.shortTermMemoryMessages("tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:memory:short_term:messages:tenant1:user1:session1");
        assertThat(redisKeys.agentDataPersistencePolicy(
                "fin_ex:agent_data_persistence", "tenant1", "domain-agent", "skill1"))
                .isEqualTo("fin_ex:dev:agent_data_persistence:tenant1:domain-agent:skill1");
        assertThat(redisKeys.runStopControlChannel("instance-a"))
                .isEqualTo("fin_ex:dev:chat_run_stop_control:instance-a");
    }

    @Test
    void canRecoverTopicIdFromCurrentEnvironmentChannel() {
        FinanceExRedisKeyBuilder redisKeys = FinanceExRedisKeyBuilder.ofEnv("dev");
        String channel = redisKeys.chatStreamChannel("chat-run-run1");

        assertThat(channel).isEqualTo("fin_ex:dev:chat_stream:chat-run-run1");
        assertThat(redisKeys.topicFromChatStreamChannel(channel)).isEqualTo("chat-run-run1");
        assertThat(redisKeys.topicFromChatStreamChannel("fin_ex:test:chat_stream:chat-run-run1"))
                .isEqualTo("fin_ex:test:chat_stream:chat-run-run1");
    }

    @Test
    void rejectsInvalidLogicalPrefixFromConfiguration() {
        ChatRunCacheProperties properties = new ChatRunCacheProperties();
        properties.setActiveKeyPrefix("bad:chat_run:active");
        FinanceExRedisKeyBuilder redisKeys = new FinanceExRedisKeyBuilder(new MockEnvironment(),
                new RuntimeBindingProperties(), properties,
                new ChatLiveEventBusProperties(), new ShortTermMemoryRedisProperties());

        assertThatThrownBy(() -> redisKeys.activeRun("tenant1", "user1", "session1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fin_ex");
    }
}
