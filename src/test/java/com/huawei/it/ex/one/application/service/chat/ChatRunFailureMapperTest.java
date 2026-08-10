package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitExceededException;
import com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitType;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;

import org.junit.jupiter.api.Test;

class ChatRunFailureMapperTest {

    @Test
    void mapsNestedRuntimeStreamLimitToStableFailurePayload() {
        RuntimeException failure = new IllegalStateException("post processing failed",
                new RuntimeStreamLimitExceededException(
                        RuntimeStreamLimitType.ASSISTANT_INSTANCE_BYTES,
                        "assistant instance budget exceeded"));

        ErrorEvent event = new ChatRunFailureMapper().toEvent("run1", "session1", failure);

        assertThat(event.type()).isEqualTo("run.failed");
        assertThat(event.code()).isEqualTo(RuntimeStreamLimitExceededException.CODE);
        assertThat(event.payload())
                .containsEntry("code", RuntimeStreamLimitExceededException.CODE)
                .containsEntry("limitType", "ASSISTANT_INSTANCE_BYTES");
    }
}
