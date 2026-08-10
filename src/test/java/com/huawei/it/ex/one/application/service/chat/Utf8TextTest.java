package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

class Utf8TextTest {
    @Test
    void byteCountMatchesJdkEncodingForAsciiChineseEmojiAndMalformedSurrogates() {
        List<String> values = List.of(
                "ascii",
                "中文",
                "A中😀B",
                "\uD800x",
                "x\uDC00");

        values.forEach(value -> assertThat(Utf8Text.bytes(value))
                .isEqualTo(value.getBytes(StandardCharsets.UTF_8).length));
    }

    @Test
    void prefixKeepsUnicodeBoundaryAndReportsExactBytes() {
        String value = "A中😀B";

        assertPrefix(value, 0L, "", true);
        assertPrefix(value, 1L, "A", true);
        assertPrefix(value, 4L, "A中", true);
        assertPrefix(value, 8L, "A中😀", true);
        assertPrefix(value, 9L, value, false);
    }

    private void assertPrefix(String value, long maxBytes, String expected, boolean truncated) {
        Utf8Text.Prefix prefix = Utf8Text.prefix(value, maxBytes);
        assertThat(prefix.value()).isEqualTo(expected);
        assertThat(prefix.bytes()).isEqualTo(expected.getBytes(StandardCharsets.UTF_8).length);
        assertThat(prefix.truncated()).isEqualTo(truncated);
    }
}
