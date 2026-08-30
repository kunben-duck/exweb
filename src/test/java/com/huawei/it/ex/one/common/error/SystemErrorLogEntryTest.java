/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SystemErrorLogEntryTest {

    @Test
    void usesCodeRetryabilityAndBuildsImmutableContext() {
        SystemErrorLogEntry event = SystemErrorLogEntry.builder(
                        SystemErrorCode.REDIS_READ_FAILED, "RuntimeBinding cache read failed")
                .runId(" run_1 ")
                .sessionId("session_1")
                .operation("runtime-binding.cache.read")
                .attribute("bindingId", "binding_1")
                .build();

        assertThat(event.retryable()).isTrue();
        assertThat(event.runId()).isEqualTo("run_1");
        assertThat(event.attributes()).containsEntry("bindingId", "binding_1");
        assertThatThrownByMutation(event);
    }

    @Test
    void rejectsBlankMessageNegativeDurationAndSensitiveAttributes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SystemErrorLogEntry.builder(SystemErrorCode.REDIS_ERROR, " "));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SystemErrorLogEntry.builder(SystemErrorCode.REDIS_ERROR, "failed")
                        .durationMs(-1L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SystemErrorLogEntry.builder(SystemErrorCode.REDIS_ERROR, "failed")
                        .attribute("authorizationHeader", "secret"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SystemErrorLogEntry.builder(SystemErrorCode.REDIS_ERROR, "failed")
                        .attribute("ErrorCode", "override"));
    }

    @Test
    void resolvesRegisteredUpstreamCodeAndPreservesUnknownCodeOnFallback() {
        SystemErrorLogEntry registered = SystemErrorLogEntry.upstreamBuilder(
                        SystemErrorCode.DOMAIN_AGENT_ERROR,
                        "FN-EX-CHAT-SYS-LLM-002",
                        "DomainAgent reported an upstream failure")
                .build();
        SystemErrorLogEntry unknown = SystemErrorLogEntry.upstreamBuilder(
                        SystemErrorCode.DOMAIN_AGENT_ERROR,
                        "VENDOR-UNKNOWN-42",
                        "DomainAgent reported an unknown upstream failure")
                .build();

        assertThat(registered.error()).isEqualTo(SystemErrorCode.LLM_TIMEOUT);
        assertThat(registered.upstreamErrorCode()).isNull();
        assertThat(unknown.error()).isEqualTo(SystemErrorCode.DOMAIN_AGENT_ERROR);
        assertThat(unknown.upstreamErrorCode()).isEqualTo("VENDOR-UNKNOWN-42");
    }

    private void assertThatThrownByMutation(SystemErrorLogEntry event) {
        assertThatThrownBy(() -> event.attributes().put("next", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
