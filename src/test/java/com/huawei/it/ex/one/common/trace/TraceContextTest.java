package com.huawei.it.ex.one.common.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceContextTest {
    @Test
    void normalizesTraceIdWithoutGeneratingFallback() {
        assertThat(new TraceContext("  trace-1  ").traceId()).isEqualTo("trace-1");
        assertThat(new TraceContext("  ").hasTraceId()).isFalse();
        assertThat(TraceContext.empty().traceId()).isNull();
    }
}
