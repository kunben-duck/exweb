package com.huawei.it.ex.one.infrastructure.domainagentconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.integration.domainagentconfig.DomainAgentSkillConfiguration;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

class RedisDomainAgentSkillConfigurationCacheTest {
    private static final String KEY =
            "fin_ex:test:domain_agent_skill_config:v1:tenant-1:skill-1";

    @Test
    void writesAndReadsTheCompleteConfiguration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        DomainAgentSkillConfiguration configuration = new DomainAgentSkillConfiguration(
                "skill-1", "技能一", Boolean.FALSE, ".xlsx;.pdf");
        RedisDomainAgentSkillConfigurationCache cache = cache(redis);

        cache.put("tenant-1", "skill-1", configuration, Duration.ofMinutes(10));
        when(values.get(KEY)).thenReturn(
                "{\"skillId\":\"skill-1\",\"skillName\":\"技能一\","
                        + "\"saveSession\":false,\"attachmentType\":\".xlsx;.pdf\"}");

        verify(values).set(KEY,
                "{\"skillId\":\"skill-1\",\"skillName\":\"技能一\","
                        + "\"saveSession\":false,\"attachmentType\":\".xlsx;.pdf\"}",
                Duration.ofMinutes(10));
        assertThat(cache.get("tenant-1", "skill-1")).contains(configuration);
    }

    @Test
    void removesInvalidOrMismatchedCachedConfiguration() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(KEY)).thenReturn("{\"skillId\":\"skill-2\"}");
        RedisDomainAgentSkillConfigurationCache cache = cache(redis);

        assertThat(cache.get("tenant-1", "skill-1")).isEmpty();
        verify(redis).delete(KEY);
    }

    private RedisDomainAgentSkillConfigurationCache cache(StringRedisTemplate redis) {
        return new RedisDomainAgentSkillConfigurationCache(
                redis,
                new ObjectMapper(),
                FinanceExRedisKeyBuilder.ofEnv("test"),
                new DomainAgentSkillConfigurationProperties());
    }
}
