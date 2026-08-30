/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.domain.chat.ChatSharePage;
import com.huawei.it.ex.one.domain.chat.ChatShareSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

class MyBatisChatShareRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void ownerPageMapsMetadataWithoutDeserializingSnapshots() {
        ChatShareMapper mapper = mock(ChatShareMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        MyBatisChatShareRepository repository = new MyBatisChatShareRepository(mapper, objectMapper);
        String sharedMaximumSnapshot = "x".repeat(5 * 1024 * 1024);
        List<ChatShareRow> rows = IntStream.range(0, 100)
                .mapToObj(index -> summaryRow(index, sharedMaximumSnapshot))
                .toList();
        when(mapper.countByOwner("tenant1", "user1")).thenReturn(100L);
        when(mapper.findPageByOwner("tenant1", "user1", 100, 0L)).thenReturn(rows);

        ChatSharePage page = repository.pageByOwner("tenant1", "user1", 1, 100);

        verifyNoInteractions(objectMapper);
        assertThat(page.items()).hasSize(100);
        assertThat(page.totalRows()).isEqualTo(100L);
        assertThat(page.totalPages()).isEqualTo(1L);
        assertThat(page.items().getFirst())
                .satisfies(summary -> {
                    assertThat(summary.id()).isEqualTo("share_0");
                    assertThat(summary.title()).isEqualTo("分享 0");
                    assertThat(summary.scope()).isEqualTo("SELECTED_MESSAGES");
                    assertThat(summary.sourceUserMessageId()).isEqualTo("msg_user_0");
                    assertThat(summary.sourceAssistantMessageId()).isNull();
                    assertThat(summary.sourceRunId()).isNull();
                    assertThat(summary.createdAt()).isEqualTo(NOW);
                });
    }

    @Test
    void detailLookupStillDeserializesFullSnapshot() throws Exception {
        ChatShareMapper mapper = mock(ChatShareMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        MyBatisChatShareRepository repository = new MyBatisChatShareRepository(mapper, objectMapper);
        ChatShareRow row = summaryRow(1, "{\"question\":null}");
        ChatShareSnapshot snapshot = new ChatShareSnapshot(null, null, List.of(), NOW);
        when(mapper.findById("share_1")).thenReturn(row);
        when(objectMapper.readValue(row.getSnapshotJson(), ChatShareSnapshot.class)).thenReturn(snapshot);

        var share = repository.findById("share_1");

        assertThat(share).isPresent().get().extracting(value -> value.snapshot()).isEqualTo(snapshot);
        verify(objectMapper).readValue(row.getSnapshotJson(), ChatShareSnapshot.class);
    }

    private ChatShareRow summaryRow(int index, String snapshotJson) {
        ChatShareRow row = new ChatShareRow();
        row.setId("share_" + index);
        row.setTenantId("tenant1");
        row.setOwnerUserId("user1");
        row.setSourceSessionId("session1");
        row.setSourceUserMessageId("msg_user_" + index);
        row.setSourceAssistantMessageId(null);
        row.setSourceRunId(null);
        row.setTitle("分享 " + index);
        row.setScope("SELECTED_MESSAGES");
        row.setVisibility("INTERNAL");
        row.setStatus("ACTIVE");
        row.setExpiresAt(null);
        row.setRevokedAt(null);
        row.setSnapshotJson(snapshotJson);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        return row;
    }
}
