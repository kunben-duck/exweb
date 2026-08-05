package com.huawei.it.ex.one.infrastructure.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.integration.conversation.SessionAppCategory;
import com.huawei.it.ex.one.application.integration.conversation.SessionListFilter;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionNumberPage;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

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
        assertThat(mapper.lastCursorTitlePattern).isNull();
        assertThat(decodeCursor(firstPage.nextCursor())).startsWith("v2|");
        assertThatThrownBy(() -> repository.pageByTenantIdAndUserId(
                "tenant1", "user1", "tax-app", firstPage.nextCursor(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor 与当前 appId 过滤条件不一致");
    }

    @Test
    void titleSearchEscapesWildcardsAndBindsVersionThreeCursor() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.pageRows = List.of(
                row("session-2", "fund-app", Instant.parse("2026-07-13T02:00:00Z")),
                row("session-1", "fund-app", Instant.parse("2026-07-13T01:00:00Z"))
        );
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        ChatSessionPage firstPage = repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter(" fund-app ", " Profit%_! "), null, 1);

        assertThat(mapper.lastAppId).isEqualTo("fund-app");
        assertThat(mapper.lastCursorTitlePattern).isEqualTo("%profit!%!_!!%");
        assertThat(decodeCursor(firstPage.nextCursor())).startsWith("v3|");
        assertThat(repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "PROFIT%_!"),
                firstPage.nextCursor(), 1).items())
                .isNotEmpty();
        assertThatThrownBy(() -> repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "cost"), firstPage.nextCursor(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor 与当前 title 过滤条件不一致");
    }

    @Test
    void versionTwoCursorCannotBeReusedAfterAddingTitleFilter() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.pageRows = List.of(
                row("session-2", "fund-app", Instant.parse("2026-07-13T02:00:00Z")),
                row("session-1", "fund-app", Instant.parse("2026-07-13T01:00:00Z"))
        );
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);
        ChatSessionPage firstPage = repository.pageByTenantIdAndUserId(
                "tenant1", "user1", "fund-app", null, 1);

        assertThatThrownBy(() -> repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "利润"), firstPage.nextCursor(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor 与当前 title 过滤条件不一致");
    }

    @Test
    void channelFilterBindsVersionFourCursorAndCannotBeChanged() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.pageRows = List.of(
                row("session-2", "fund-app", Instant.parse("2026-07-13T02:00:00Z")),
                row("session-1", "fund-app", Instant.parse("2026-07-13T01:00:00Z"))
        );
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        ChatSessionPage firstPage = repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "利润", " mobile "), null, 1);

        assertThat(mapper.lastCursorChannel).isEqualTo("mobile");
        assertThat(decodeCursor(firstPage.nextCursor())).startsWith("v4|");
        assertThat(repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "利润", "mobile"),
                firstPage.nextCursor(), 1).items()).isNotEmpty();
        assertThatThrownBy(() -> repository.pageByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", "利润", null),
                firstPage.nextCursor(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor 与当前 channel 过滤条件不一致");
    }

    @Test
    void numberPageUsesSameTitlePatternForCountAndRows() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.totalRows = 3;
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        ChatSessionNumberPage page = repository.pageNumberByTenantIdAndUserId(
                "tenant1", "user1", new SessionListFilter("fund-app", " PROFIT ", "mobile"), 1, 2);

        assertThat(mapper.lastCountTitlePattern).isEqualTo("%profit%");
        assertThat(mapper.lastNumberPageTitlePattern).isEqualTo("%profit%");
        assertThat(mapper.lastCountChannel).isEqualTo("mobile");
        assertThat(mapper.lastNumberPageChannel).isEqualTo("mobile");
        assertThat(page.totalRows()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
    }

    @Test
    void appCategoriesUseDedicatedLightweightMapperQuery() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.appRows = List.of(
                appRow("fund-app", "资金助手", Instant.parse("2026-08-03T02:00:00Z")),
                appRow("tax-app", null, Instant.parse("2026-08-03T01:00:00Z"))
        );
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        List<SessionAppCategory> apps = repository.findAppsByTenantIdAndUserId("tenant1", "user1");

        assertThat(apps)
                .extracting(app -> app.appId() + "|" + app.appName())
                .containsExactly("fund-app|资金助手", "tax-app|null");
        assertThat(mapper.lastAppTenantId).isEqualTo("tenant1");
        assertThat(mapper.lastAppUserId).isEqualTo("user1");
        assertThat(mapper.lastAppChannel).isNull();
        assertThat(mapper.findByOwnerCalls).isZero();
    }

    @Test
    void appCategoriesPassNormalizedChannelToMapper() {
        RecordingMapper mapper = new RecordingMapper();
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        repository.findAppsByTenantIdAndUserId("tenant1", "user1", " mobile ");

        assertThat(mapper.lastAppChannel).isEqualTo("mobile");
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

    @Test
    void sessionPaginationCapsRequestedPageSizeAtTwoHundred() {
        RecordingMapper mapper = new RecordingMapper();
        mapper.totalRows = 1_000;
        MyBatisSessionRepository repository = new MyBatisSessionRepository(mapper);

        repository.pageByTenantIdAndUserId("tenant1", "user1", null, 1_000);
        ChatSessionNumberPage numberPage = repository.pageNumberByTenantIdAndUserId(
                "tenant1", "user1", 1, 1_000);

        assertThat(mapper.lastCursorLimit).isEqualTo(201);
        assertThat(mapper.lastNumberPageLimit).isEqualTo(200);
        assertThat(numberPage.pageSize()).isEqualTo(200);
        assertThat(numberPage.totalPages()).isEqualTo(5);
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

    private static ChatSessionAppRow appRow(String appId, String appName, Instant latestActivityAt) {
        ChatSessionAppRow row = new ChatSessionAppRow();
        row.setAppId(appId);
        row.setAppName(appName);
        row.setLatestActivityAt(latestActivityAt);
        return row;
    }

    private static String decodeCursor(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }

    private static final class RecordingMapper implements ChatSessionMapper {
        private List<ChatSessionRow> pageRows = List.of();
        private List<ChatSessionAppRow> appRows = List.of();
        private String lastAppId;
        private String lastAppTenantId;
        private String lastAppUserId;
        private String lastAppChannel;
        private String lastCursorTitlePattern;
        private String lastCursorChannel;
        private String lastCountTitlePattern;
        private String lastCountChannel;
        private String lastNumberPageTitlePattern;
        private String lastNumberPageChannel;
        private int lastCursorLimit;
        private int lastNumberPageLimit;
        private long totalRows;
        private ChatSessionRow ownerRow;
        private int findByOwnerCalls;
        private long lastReadThroughSeq;

        @Override public int insert(ChatSessionRow row) { return 1; }
        @Override public int update(ChatSessionRow row) { return 1; }
        @Override public ChatSessionRow findById(String sessionId) { return null; }
        @Override public ChatSessionRow findByOwnerAndId(String tenantId, String userId, String sessionId) { return ownerRow; }
        @Override
        public List<ChatSessionRow> findByOwner(String tenantId, String userId) {
            findByOwnerCalls++;
            return List.of();
        }

        @Override
        public List<ChatSessionAppRow> findAppsByOwner(String tenantId, String userId, String channel) {
            lastAppTenantId = tenantId;
            lastAppUserId = userId;
            lastAppChannel = channel;
            return appRows;
        }

        @Override
        public List<ChatSessionRow> findPageByOwner(String tenantId, String userId, String appId, String titlePattern,
                                                    String channel, Instant cursorUpdatedAt, String cursorId, int limit) {
            lastAppId = appId;
            lastCursorTitlePattern = titlePattern;
            lastCursorChannel = channel;
            lastCursorLimit = limit;
            return pageRows;
        }

        @Override
        public long countPageByOwner(
                String tenantId, String userId, String appId, String titlePattern, String channel) {
            lastCountTitlePattern = titlePattern;
            lastCountChannel = channel;
            return totalRows;
        }

        @Override public List<ChatSessionRow> findNumberPageByOwner(
                String tenantId, String userId, String appId, String titlePattern, String channel,
                int limit, long offset) {
            lastNumberPageTitlePattern = titlePattern;
            lastNumberPageChannel = channel;
            lastNumberPageLimit = limit;
            return List.of();
        }
        @Override public Long lockNodeOrder(String tenantId, String userId, String sessionId) { return 0L; }
        @Override public int updateNodeOrder(
                String tenantId, String userId, String sessionId, long lastNodeOrder, Instant updatedAt) { return 1; }
        @Override public int updateCurrentLeaf(
                String tenantId, String userId, String sessionId, String leafMessageId, Instant updatedAt) { return 1; }
        @Override public int touch(
                String tenantId, String userId, String sessionId, Instant updatedAt) { return 1; }
        @Override public int updateTitleWithoutTouch(
                String tenantId, String userId, String sessionId, String title, String metadataJson) { return 1; }
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
