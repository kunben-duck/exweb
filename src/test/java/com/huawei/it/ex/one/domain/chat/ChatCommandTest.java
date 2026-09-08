/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ChatCommandTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n", "\u2003"})
    void blankTagsAndAbsentCollectionsKeepExistingDefaults(String value) {
        ChatCommand command = command(value, value, value, value);

        assertThat(command.language()).isNull();
        assertThat(command.intentAccessName()).isNull();
        assertThat(command.appId()).isNull();
        assertThat(command.appName()).isNull();
        assertThat(command.runMode()).isEqualTo(ChatRunMode.NEXT);
        assertThat(command.attachments()).isEmpty();
        assertThat(command.metadata()).isEmpty();
        assertThat(command.questionnaireAnswers()).isEmpty();
    }

    @Test
    void trimsTagsWithoutChangingCaseOrStrippingUnicodeAroundText() {
        ChatCommand command = command(" zh_CN ", " Fin_PC ", " App ", "\u2003Name\u2003");

        assertThat(command.targetType()).isEqualTo("DOMAIN_AGENT");
        assertThat(command.targetId()).isEqualTo("Skill_A");
        assertThat(command.routeTrigger()).isEqualTo("user_correction");
        assertThat(command.interactionId()).isEqualTo("interaction1");
        assertThat(command.interactionAction()).isEqualTo("OTHER");
        assertThat(command.scope()).isEqualTo("once");
        assertThat(command.language()).isEqualTo("zh_CN");
        assertThat(command.intentAccessName()).isEqualTo("Fin_PC");
        assertThat(command.appId()).isEqualTo("App");
        assertThat(command.appName()).isEqualTo("\u2003Name\u2003");
    }

    @Test
    void lengthBoundariesContinueToCountUtf16UnitsAfterTrimming() {
        String supplementary = "\uD83D\uDE00";
        ChatCommand command = command(" " + supplementary.repeat(16) + " ",
                supplementary.repeat(64), "a".repeat(128), "b".repeat(256));

        assertThat(command.language()).hasSize(32);
        assertThat(command.intentAccessName()).hasSize(128);
        assertThat(command.appId()).hasSize(128);
        assertThat(command.appName()).hasSize(256);
        assertThatThrownBy(() -> command(supplementary.repeat(17), null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("language 长度不能超过 32");
        assertThatThrownBy(() -> command(null, supplementary.repeat(65), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("intentAccessName 长度不能超过 128");
    }

    @Test
    void rejectsMultipleInvalidFieldsInTheOriginalOrder() {
        assertThatThrownBy(() -> command("x".repeat(33), "x".repeat(129), null, "name"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("language 长度不能超过 32");
        assertThatThrownBy(() -> command(null, "x".repeat(129), null, "name"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("intentAccessName 长度不能超过 128");
        assertThatThrownBy(() -> command(null, null, null, "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("appName 不能脱离 appId 单独使用");
        assertThatThrownBy(() -> command(null, null, "x".repeat(129), "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("appId 长度不能超过 128");
        assertThatThrownBy(() -> command(null, null, "app", "x".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("appName 长度不能超过 256");
    }

    @Test
    void collectionsAreStillCopiedAndNullEntriesAreRejected() {
        List<AttachmentRef> attachments = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>(Map.of("key", "original"));
        ChatCommand command = new ChatCommand("cmd", "tenant", "user", "session", null,
                "web", "query", attachments, metadata);
        metadata.put("key", "changed");

        assertThat(command.metadata()).containsEntry("key", "original");
        assertThatThrownBy(() -> command.metadata().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        attachments.add(null);
        assertThat(command.attachments()).isEmpty();
        assertThatThrownBy(() -> new ChatCommand("cmd", "tenant", "user", "session", null,
                "web", "query", attachments, metadata)).isInstanceOf(NullPointerException.class);
    }

    private ChatCommand command(String language, String entry, String appId, String appName) {
        return new ChatCommand("cmd", "tenant", "user", "session", null, "web", "query",
                null, null, " DOMAIN_AGENT ", " Skill_A ", null, null, null, null,
                " user_correction ", " interaction1 ", null, " once ", null,
                appId, appName, null, " OTHER ", language, entry, null);
    }
}
