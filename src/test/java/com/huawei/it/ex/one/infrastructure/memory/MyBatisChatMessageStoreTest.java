package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

class MyBatisChatMessageStoreTest {
    private static final String MEMORY_QUERY_TIMEOUT_PROPERTY =
            "${financeex.memory.short-term.storage.database-query-timeout-seconds:2}";

    @Test
    void recentMessageQueriesUseReadOnlyConfiguredTransactionTimeout() throws NoSuchMethodException {
        Method defaultPath = MyBatisChatMessageStore.class.getMethod(
                "findRecentMessages", String.class, String.class, String.class, int.class);
        Method selectedPath = MyBatisChatMessageStore.class.getMethod(
                "findRecentMessages", String.class, String.class, String.class, String.class, int.class);

        assertReadOnlyMemoryQuery(defaultPath);
        assertReadOnlyMemoryQuery(selectedPath);
    }

    @Test
    void ownedMessageRoleLookupUsesOnlyTheLightweightMapperQuery() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.findRoleByOwnerAndId("tenant-1", "user-1", "message-1"))
                .thenReturn(java.util.Optional.of("user"));
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        assertThat(store.findRoleByOwnerAndId("tenant-1", "user-1", "message-1"))
                .contains("user");

        verify(mapper).findRoleByOwnerAndId("tenant-1", "user-1", "message-1");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void savesTerminalPartsInOrderedMultiRowBatches() {
        ChatMessageMapper mapper = successfulMapper();
        MyBatisChatMessageStore store = store(mapper, 2, DataSize.ofMegabytes(1));

        store.save(message(List.of(
                part("part-1", 1, "one"),
                part("part-2", 2, "two"),
                part("part-3", 3, "three"),
                part("part-4", 4, "four"),
                part("part-5", 5, "five")
        )));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessagePartRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(3)).insertParts(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(captor.getAllValues().stream()
                .flatMap(List::stream)
                .map(ChatMessagePartRow::getId))
                .containsExactly("part-1", "part-2", "part-3", "part-4", "part-5");
    }

    @Test
    void writesAnOversizedPartAsItsOwnBatch() {
        ChatMessageMapper mapper = successfulMapper();
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofBytes(1));

        store.updateAssistantMessage(message(List.of(
                part("part-1", 1, "one"),
                part("part-2", 2, "two")
        )));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessagePartRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(2)).insertParts(captor.capture());
        assertThat(captor.getAllValues()).extracting(List::size).containsExactly(1, 1);
    }

    @Test
    void rejectsUnexpectedBatchInsertCount() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.insertParts(anyList())).thenReturn(1);
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        assertThatThrownBy(() -> store.save(message(List.of(
                part("part-1", 1, "one"),
                part("part-2", 2, "two")
        ))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expected=2, actual=1");
    }

    @Test
    void singlePartCompatibilityWriteUsesBatchMapper() {
        ChatMessageMapper mapper = successfulMapper();
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        store.savePart(part("part-1", 1, "one"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessagePartRow>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).insertParts(captor.capture());
        assertThat(captor.getValue()).singleElement()
                .extracting(ChatMessagePartRow::getId)
                .isEqualTo("part-1");
    }

    @Test
    void pagesOneHundredTwentyPathMessagesWithoutDuplicatesOrOmissions() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.findActivePathPage(
                eq("tenant-1"), eq("user-1"), eq("session-1"), any(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    String pageStart = invocation.getArgument(3);
                    String leaf = invocation.getArgument(4);
                    int fetchLimit = invocation.getArgument(5);
                    int start = messageNumber(pageStart != null ? pageStart : leaf);
                    if (start == 0) {
                        start = 120;
                    }
                    return descendingRows(start, fetchLimit);
                });
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        ChatMessagePage first = store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null, null, 50));
        ChatMessagePage second = store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null, first.nextCursor(), 50));
        ChatMessagePage third = store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null, second.nextCursor(), 25));

        assertThat(first.items()).extracting(ChatMessage::id)
                .containsExactlyElementsOf(messageIds(71, 120));
        assertThat(second.items()).extracting(ChatMessage::id)
                .containsExactlyElementsOf(messageIds(21, 70));
        assertThat(third.items()).extracting(ChatMessage::id)
                .containsExactlyElementsOf(messageIds(1, 20));
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.nextCursor()).isNotBlank();
        assertThat(third.nextCursor()).isNull();

        List<String> prepended = new java.util.ArrayList<>();
        prepended.addAll(third.items().stream().map(ChatMessage::id).toList());
        prepended.addAll(second.items().stream().map(ChatMessage::id).toList());
        prepended.addAll(first.items().stream().map(ChatMessage::id).toList());
        assertThat(prepended).containsExactlyElementsOf(messageIds(1, 120));
        verify(mapper, never()).findActivePath(any(), any(), any(), any());
    }

    @Test
    void cursorPinsInitialLeafAndRejectsMismatchedLeafOrSession() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.findActivePathPage(
                eq("tenant-1"), eq("user-1"), eq("session-1"), any(), any(), anyInt()))
                .thenReturn(descendingRows(5, 3));
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        ChatMessagePage first = store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", "message-5", null, 2));

        assertThatThrownBy(() -> store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", "message-other",
                        first.nextCursor(), 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leafMessageId");
        assertThatThrownBy(() -> store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-2", null,
                        first.nextCursor(), 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前会话");
    }

    @Test
    void rejectsDamagedOrMissingCursorTarget() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.findActivePathPage(
                eq("tenant-1"), eq("user-1"), eq("session-1"), any(), any(), anyInt()))
                .thenReturn(descendingRows(3, 2))
                .thenReturn(List.of());
        MyBatisChatMessageStore store = store(mapper, 100, DataSize.ofMegabytes(1));

        assertThatThrownBy(() -> store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null, "damaged", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("游标无效");

        ChatMessagePage first = store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null, null, 1));
        assertThatThrownBy(() -> store.pageMessages(
                new ChatMessagePageQuery("tenant-1", "user-1", "session-1", null,
                        first.nextCursor(), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或不属于当前路径");
    }

    private ChatMessageMapper successfulMapper() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.updateAssistant(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertParts(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());
        return mapper;
    }

    private void assertReadOnlyMemoryQuery(Method method) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.timeoutString()).isEqualTo(MEMORY_QUERY_TIMEOUT_PROPERTY);
    }

    private MyBatisChatMessageStore store(ChatMessageMapper mapper, int maxSize, DataSize maxBytes) {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setAssistantPartBatchMaxSize(maxSize);
        properties.setAssistantPartBatchMaxBytes(maxBytes);
        return new MyBatisChatMessageStore(mapper, new ObjectMapper(), properties);
    }

    private ChatMessage message(List<ChatMessagePart> parts) {
        return new ChatMessage(
                "message-1",
                "tenant-1",
                "user-1",
                "session-1",
                "user-message-1",
                2L,
                1,
                0,
                "assistant",
                "answer",
                null,
                "run-1",
                "NORMAL",
                false,
                null,
                null,
                null,
                null,
                null,
                parts,
                Instant.parse("2026-07-30T00:00:00Z")
        );
    }

    private ChatMessagePart part(String id, int order, String text) {
        return new ChatMessagePart(
                id,
                "tenant-1",
                "user-1",
                "session-1",
                "message-1",
                "run-1",
                "THINKING",
                "agent-reasoning",
                text,
                Map.of("text", text),
                order,
                Instant.parse("2026-07-30T00:00:00Z")
        );
    }

    private List<ChatMessageRow> descendingRows(int start, int limit) {
        return IntStream.iterate(start, value -> value > 0, value -> value - 1)
                .limit(limit)
                .mapToObj(this::messageRow)
                .toList();
    }

    private ChatMessageRow messageRow(int number) {
        ChatMessageRow row = new ChatMessageRow();
        row.setId("message-" + number);
        row.setTenantId("tenant-1");
        row.setUserId("user-1");
        row.setSessionId("session-1");
        row.setParentMessageId(number == 1 ? null : "message-" + (number - 1));
        row.setNodeOrder((long) number);
        row.setTreeDepth(number - 1);
        row.setSiblingIndex(1);
        row.setRole(number % 2 == 0 ? "assistant" : "user");
        row.setContent("content-" + number);
        row.setLocked(false);
        row.setCreatedAt(Instant.EPOCH.plusSeconds(number));
        return row;
    }

    private int messageNumber(String messageId) {
        if (messageId == null) {
            return 0;
        }
        return Integer.parseInt(messageId.substring("message-".length()));
    }

    private List<String> messageIds(int first, int last) {
        return IntStream.rangeClosed(first, last).mapToObj(value -> "message-" + value).toList();
    }
}
