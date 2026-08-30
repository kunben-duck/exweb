/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.share;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class ChatShareTitleNormalizerTest {
    @Test
    void preservesOneHundredTwentyAsciiCharactersAndTruncatesTheRemainder() {
        String title = "a".repeat(121);

        String normalized = ChatShareTitleNormalizer.normalize(title, "fallback");

        assertThat(normalized).isEqualTo("a".repeat(120));
    }

    @Test
    void truncatesChineseTitleAtUtf8ByteBoundary() {
        String title = "中".repeat(85) + "a中";

        String normalized = ChatShareTitleNormalizer.normalize(title, "fallback");

        assertThat(normalized).isEqualTo("中".repeat(85) + "a");
        assertThat(normalized.getBytes(StandardCharsets.UTF_8)).hasSize(256);
    }

    @Test
    void truncatesSupplementaryCharactersWithoutSplittingSurrogatePairs() {
        String title = "😀".repeat(65);

        String normalized = ChatShareTitleNormalizer.normalize(title, "fallback");

        assertThat(normalized).isEqualTo("😀".repeat(64));
        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(64);
        assertThat(normalized.getBytes(StandardCharsets.UTF_8)).hasSize(256);
    }

    @Test
    void normalizesWhitespaceAndUsesFallbackForBlankTitle() {
        assertThat(ChatShareTitleNormalizer.normalize("  标题\n  内容  ", "fallback"))
                .isEqualTo("标题 内容");
        assertThat(ChatShareTitleNormalizer.normalize("  ", "默认标题"))
                .isEqualTo("默认标题");
    }
}
