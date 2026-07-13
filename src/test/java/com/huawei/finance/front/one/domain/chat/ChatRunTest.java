package com.huawei.finance.front.one.domain.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatRunTest {
    @Test
    void runModeParsingDoesNotDependOnDefaultLocale() {
        Locale previousLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(ChatRunMode.from("edit_user")).isEqualTo(ChatRunMode.EDIT_USER);
        } finally {
            Locale.setDefault(previousLocale);
        }
    }

    @Test
    void withFirstSeqSetsItOnceAndAlwaysAdvancesLastSeq() {
        ChatRun initial = new ChatRun(
                "run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT,
                null, "user-message-1", null, null, null, null,
                Instant.EPOCH, null, Map.of(), Instant.EPOCH, Instant.EPOCH);

        ChatRun started = initial.withFirstSeq(10L);
        ChatRun updated = started.withFirstSeq(20L);

        assertThat(started.firstSeq()).isEqualTo(10L);
        assertThat(started.lastSeq()).isEqualTo(10L);
        assertThat(updated.firstSeq()).isEqualTo(10L);
        assertThat(updated.lastSeq()).isEqualTo(20L);
    }
}
