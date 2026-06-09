package com.huawei.finance.front.one.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.ChatReadCursorProperties;
import com.huawei.finance.front.one.infrastructure.memory.RedisShortTermMemoryCache;
import com.huawei.finance.front.one.infrastructure.memory.ShortTermMemoryRedisProperties;
import com.huawei.finance.front.one.infrastructure.persistence.ChatLiveEventBusProperties;
import com.huawei.finance.front.one.infrastructure.persistence.ChatRunCacheProperties;
import com.huawei.finance.front.one.infrastructure.persistence.RedisChatLiveEventBus;
import com.huawei.finance.front.one.infrastructure.persistence.RedisChatReadCursorCache;
import com.huawei.finance.front.one.infrastructure.persistence.RedisChatRunCache;
import com.huawei.finance.front.one.infrastructure.runtime.RedisRuntimeBindingCache;
import com.huawei.finance.front.one.infrastructure.runtime.RuntimeBindingProperties;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class FinanceExRedisKeyUsageTest {
    private final FinanceExRedisKeyNamespace namespace = FinanceExRedisKeyNamespace.ofEnv("dev");

    @Test
    void runtimeBindingKeysIncludeEnvAndKeepSessionHashTag() throws Exception {
        RedisRuntimeBindingCache cache = new RedisRuntimeBindingCache(null, new ObjectMapper(),
                new RuntimeBindingProperties(), namespace);

        assertThat(invokeString(cache, "key", "tenant1", "user1", "session1", "leaf1"))
                .isEqualTo("fin_ex:dev:runtime_binding:{tenant1:user1:session1}:leaf1");
        assertThat(invokeString(cache, "indexKey", "tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:runtime_binding:index:{tenant1:user1:session1}");
    }

    @Test
    void chatRunKeysIncludeEnv() throws Exception {
        RedisChatRunCache cache = new RedisChatRunCache(null, new ObjectMapper(),
                new ChatRunCacheProperties(), namespace);

        assertThat(invokeString(cache, "activeKey", "tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:chat_run:active:tenant1:user1:session1");
        assertThat(invokeString(cache, "cancelKey", "run1"))
                .isEqualTo("fin_ex:dev:chat_run:cancel:run1");
        assertThat(invokeString(cache, "recoverLockKey", "run1"))
                .isEqualTo("fin_ex:dev:chat_run:recover_lock:run1");
    }

    @Test
    void readCursorAndShortTermMemoryKeysIncludeEnv() throws Exception {
        RedisChatReadCursorCache cursorCache = new RedisChatReadCursorCache(null,
                new ChatReadCursorProperties(), namespace);
        RedisShortTermMemoryCache memoryCache = new RedisShortTermMemoryCache(null, new ObjectMapper(),
                new ShortTermMemoryRedisProperties(), namespace);

        assertThat(invokeString(cursorCache, "key", "tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:chat_read_cursor:tenant1:user1:session1");
        assertThat(invokeString(memoryCache, "key", "tenant1", "user1", "session1"))
                .isEqualTo("fin_ex:dev:memory:short_term:messages:tenant1:user1:session1");
    }

    @Test
    void liveEventBusChannelIncludesEnvAndCanRecoverTopicId() throws Exception {
        RedisChatLiveEventBus bus = new RedisChatLiveEventBus(null, new ObjectMapper(),
                new ChatLiveEventBusProperties(), connectionFactoryProxy(), namespace);

        String channel = invokeString(bus, "channel", "chat-run-run1");

        assertThat(channel).isEqualTo("fin_ex:dev:chat_stream:chat-run-run1");
        assertThat(invokeString(bus, "topicFromChannel", channel)).isEqualTo("chat-run-run1");
    }

    private String invokeString(Object target, String methodName, Object... args) throws Exception {
        Method method = findMethod(target.getClass(), methodName, args.length);
        method.setAccessible(true);
        return (String) method.invoke(target, args);
    }

    private Method findMethod(Class<?> type, String name, int argCount) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == argCount) {
                return method;
            }
        }
        throw new IllegalArgumentException("Method not found: " + type.getName() + "#" + name);
    }

    private RedisConnectionFactory connectionFactoryProxy() {
        return (RedisConnectionFactory) Proxy.newProxyInstance(
                RedisConnectionFactory.class.getClassLoader(),
                new Class<?>[] {RedisConnectionFactory.class},
                (proxy, method, args) -> {
                    Class<?> returnType = method.getReturnType();
                    if (boolean.class.equals(returnType)) {
                        return false;
                    }
                    if (int.class.equals(returnType)) {
                        return 0;
                    }
                    return null;
                });
    }
}
