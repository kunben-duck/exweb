package com.huawei.it.ex.one.infrastructure.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class MyBatisChatMessageStoreTest {
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

    private ChatMessageMapper successfulMapper() {
        ChatMessageMapper mapper = mock(ChatMessageMapper.class);
        when(mapper.updateAssistant(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(mapper.insertParts(anyList())).thenAnswer(invocation -> invocation.<List<?>>getArgument(0).size());
        return mapper;
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
}
