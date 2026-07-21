package com.huawei.it.ex.one.chat.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MyBatisSessionRepositoryTest {
    @Test
    void cursorCannotBeReusedWithAnotherAppIdFilter() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.pageRows = List.of(
                row("session-2", "fund-app", Instant.parse("2026-07-13T02:00:00Z")),
                row("session-1", "fund-app", Instant.parse("2026-07-13T01:00:00Z"))
        );
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        ChatSessionPage firstPage = repository.pageByTenantIdAndUserId(
                "tenant1", "user1", " fund-app ", null, 1);

        assertThat(firstPage.items()).extracting(session -> session.id()).containsExactly("session-2");
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(mapper.lastAppId).isEqualTo("fund-app");
        assertThatThrownBy(() -> repository.pageByTenantIdAndUserId(
                "tenant1", "user1", "tax-app", firstPage.nextCursor(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor 与当前 appId 过滤条件不一致");
    }

    @Test
    void unreadWatermarksUseDedicatedMapperOperations() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.ownerRow = row("session-1", "fund-app", Instant.parse("2026-07-13T02:00:00Z"));
        mapper.ownerRow.setLatestMessageSeq(30L);
        mapper.ownerRow.setLastReadSeq(10L);
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        repository.advanceLatestMessageSeq("tenant1", "user1", "session-1", 40L);
        ChatSession marked = repository.markReadThrough("tenant1", "user1", "session-1", 999L);

        assertThat(mapper.ownerRow.getLatestMessageSeq()).isEqualTo(40L);
        assertThat(mapper.lastReadThroughSeq).isEqualTo(999L);
        assertThat(marked.latestMessageSeq()).isEqualTo(40L);
        assertThat(marked.lastReadSeq()).isEqualTo(40L);
        assertThat(marked.hasUnread()).isFalse();
    }

    private static ChatSessionRow row(String id, String appId, Instant updatedAt) {
        ChatSessionRow row = new ChatSessionRow();
        row.setId(id);
        row.setTenantId("tenant1");
        row.setUserId("user1");
        row.setTitle(id);
        row.setStatus("ACTIVE");
        row.setChannel("web");
        row.setAppId(appId);
        row.setAppName("资金助手");
        row.setRootSessionId(id);
        row.setLastNodeOrder(0L);
        row.setCreatedAt(updatedAt.minusSeconds(1));
        row.setUpdatedAt(updatedAt);
        return row;
    }

    private static final class RecordingMapper implements ChatSessionMapper {
        private List<ChatSessionRow> pageRows = List.of();
        private String lastAppId;
        private ChatSessionRow ownerRow;
        private long lastReadThroughSeq;

        @Override public int insert(ChatSessionRow row) { return 1; }
        @Override public int update(ChatSessionRow row) { return 1; }
        @Override public ChatSessionRow findById(String sessionId) { return null; }
        @Override public ChatSessionRow findByOwnerAndId(String tenantId, String userId, String sessionId) { return ownerRow; }
        @Override public List<ChatSessionRow> findByOwner(String tenantId, String userId) { return List.of(); }

        @Override
        public List<ChatSessionRow> findPageByOwner(String tenantId, String userId, String appId,
                                                    Instant cursorUpdatedAt, String cursorId, int limit) {
            lastAppId = appId;
            return pageRows;
        }

        @Override public long countPageByOwner(String tenantId, String userId, String appId) { return 0; }
        @Override public List<ChatSessionRow> findNumberPageByOwner(
                String tenantId, String userId, String appId, int limit, long offset) { return List.of(); }
        @Override public Long lockNodeOrder(String tenantId, String userId, String sessionId) { return 0L; }
        @Override public int updateNodeOrder(
                String tenantId, String userId, String sessionId, long lastNodeOrder, Instant updatedAt) { return 1; }
        @Override public int updateCurrentLeaf(
                String tenantId, String userId, String sessionId, String leafMessageId, Instant updatedAt) { return 1; }
        @Override public int advanceLatestMessageSeq(
                String tenantId, String userId, String sessionId, long messageSeq) {
            ownerRow.setLatestMessageSeq(Math.max(ownerRow.getLatestMessageSeq(), messageSeq));
            return 1;
        }
        @Override public int markReadThrough(
                String tenantId, String userId, String sessionId, long readThroughSeq) {
            lastReadThroughSeq = readThroughSeq;
            ownerRow.setLastReadSeq(Math.max(ownerRow.getLastReadSeq(),
                    Math.min(readThroughSeq, ownerRow.getLatestMessageSeq())));
            return 1;
        }
    }
}
