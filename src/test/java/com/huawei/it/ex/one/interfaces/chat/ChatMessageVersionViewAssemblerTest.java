/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageVersionInfoDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageVersionItemDto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ChatMessageVersionViewAssemblerTest {
    private final ChatMessageVersionViewAssembler assembler = new ChatMessageVersionViewAssembler();

    @Test
    void assembleReturnsEmptyWhenMessageHasNoSiblingVersion() {
        ChatMessage question = message("q1", null, 1, 0, 1, "user");
        ChatMessage answer = message("a1", "q1", 2, 1, 1, "assistant");

        Map<String, ChatMessageVersionInfoDto> result = assembler.assemble(
                List.of(question, answer), List.of(question, answer));

        assertThat(result).isEmpty();
    }

    @Test
    void userVersionSwitchLeafUsesDeepestAssistantInThatBranch() {
        ChatMessage q1 = message("q1", null, 1, 0, 1, "user");
        ChatMessage a1 = message("a1", "q1", 2, 1, 1, "assistant");
        ChatMessage q4 = message("q4", "a1", 3, 2, 1, "user");
        ChatMessage a4 = message("a4", "q4", 4, 3, 1, "assistant");
        ChatMessage q2 = message("q2", null, 5, 0, 2, "user");
        ChatMessage a2 = message("a2", "q2", 6, 1, 1, "assistant");

        Map<String, ChatMessageVersionInfoDto> result = assembler.assemble(
                List.of(q2, a2), List.of(q1, a1, q4, a4, q2, a2));

        ChatMessageVersionInfoDto info = result.get("q2");
        assertThat(info.currentIndex()).isEqualTo(2);
        assertThat(info.total()).isEqualTo(2);
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::messageId)
                .containsExactly("q1", "q2");
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::switchLeafMessageId)
                .containsExactly("a4", "a2");
    }

    @Test
    void assistantVersionSwitchLeafCanUseDeepestVisibleLeafUnderCandidate() {
        ChatMessage q1 = message("q1", null, 1, 0, 1, "user");
        ChatMessage a1 = message("a1", "q1", 2, 1, 1, "assistant");
        ChatMessage a2 = message("a2", "q1", 3, 1, 2, "assistant");
        ChatMessage q2 = message("q2", "a2", 4, 2, 1, "user");
        ChatMessage a3 = message("a3", "q2", 5, 3, 1, "assistant");

        Map<String, ChatMessageVersionInfoDto> result = assembler.assemble(
                List.of(q1, a2, q2, a3), List.of(q1, a1, a2, q2, a3));

        ChatMessageVersionInfoDto info = result.get("a2");
        assertThat(info.currentIndex()).isEqualTo(2);
        assertThat(info.total()).isEqualTo(2);
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::messageId)
                .containsExactly("a1", "a2");
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::switchLeafMessageId)
                .containsExactly("a1", "a3");
    }

    @Test
    void roleGroupingDoesNotDependOnDefaultLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            ChatMessage first = message("a1", "q1", 1, 1, 1, "ASSISTANT");
            ChatMessage second = message("a2", "q1", 2, 1, 2, "assistant");

            Map<String, ChatMessageVersionInfoDto> result = assembler.assemble(
                    List.of(second), List.of(first, second));

            assertThat(result.get("a2").total()).isEqualTo(2);
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    void assemblePageUsesOnlyBoundedVersionCandidates() {
        ChatMessage selected = message("a2", "q1", 3, 1, 2, "assistant");
        List<ChatMessageVersionCandidate> candidates = List.of(
                candidate("a2", "a2", 2, "leaf-a2"),
                candidate("a2", "a1", 1, "leaf-a1")
        );

        Map<String, ChatMessageVersionInfoDto> result = assembler.assemblePage(List.of(selected), candidates);

        ChatMessageVersionInfoDto info = result.get("a2");
        assertThat(info.currentIndex()).isEqualTo(2);
        assertThat(info.total()).isEqualTo(2);
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::messageId)
                .containsExactly("a1", "a2");
        assertThat(info.variants()).extracting(ChatMessageVersionItemDto::switchLeafMessageId)
                .containsExactly("leaf-a1", "leaf-a2");
    }

    private ChatMessage message(String id, String parentId, long nodeOrder, int depth, int siblingIndex, String role) {
        return new ChatMessage(id, "tenant1", "user1", "session1", parentId, nodeOrder, depth, siblingIndex,
                role, role + "-" + id, null, "run1", "NORMAL", false, null, null, null, null, null,
                Instant.EPOCH.plusSeconds(nodeOrder));
    }

    private ChatMessageVersionCandidate candidate(
            String pageMessageId, String messageId, int siblingIndex, String switchLeafMessageId) {
        return new ChatMessageVersionCandidate(
                pageMessageId,
                messageId,
                "assistant",
                siblingIndex,
                false,
                "NORMAL",
                null,
                null,
                Instant.EPOCH.plusSeconds(siblingIndex),
                switchLeafMessageId
        );
    }
}
