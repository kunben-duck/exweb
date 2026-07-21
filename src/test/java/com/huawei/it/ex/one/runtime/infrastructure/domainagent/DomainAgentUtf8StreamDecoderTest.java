package com.huawei.it.ex.one.runtime.infrastructure.domainagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

class DomainAgentUtf8StreamDecoderTest {
    private final DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();

    @Test
    void preservesUtf8CodePointSplitAcrossDataBuffers() {
        String payload = "message: {\"content\":\"分析结果\"}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int split = "message: {\"content\":\"".getBytes(StandardCharsets.UTF_8).length + 1;
        DomainAgentUtf8StreamDecoder decoder = new DomainAgentUtf8StreamDecoder();

        String first = decoder.decode(buffers.wrap(Arrays.copyOfRange(bytes, 0, split)));
        String second = decoder.decode(buffers.wrap(Arrays.copyOfRange(bytes, split, bytes.length)));

        assertThat(first + second + decoder.finish()).isEqualTo(payload);
    }

    @Test
    void rejectsMalformedUtf8WithoutReturningReplacementCharacters() {
        DomainAgentUtf8StreamDecoder decoder = new DomainAgentUtf8StreamDecoder();

        assertThatThrownBy(() -> decoder.decode(buffers.wrap(new byte[]{(byte) 0xC3, 0x28})))
                .isInstanceOf(DomainAgentProtocolException.class)
                .hasMessageContaining("DOMAIN_AGENT_INVALID_UTF8");
    }

    @Test
    void rejectsIncompleteUtf8AtEndOfStream() {
        DomainAgentUtf8StreamDecoder decoder = new DomainAgentUtf8StreamDecoder();

        assertThat(decoder.decode(buffers.wrap(new byte[]{(byte) 0xE5}))).isEmpty();
        assertThatThrownBy(decoder::finish)
                .isInstanceOf(DomainAgentProtocolException.class)
                .hasMessageContaining("DOMAIN_AGENT_INVALID_UTF8");
    }
}
