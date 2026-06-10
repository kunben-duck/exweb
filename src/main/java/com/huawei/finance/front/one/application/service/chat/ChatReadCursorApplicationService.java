package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.config.ChatReadCursorProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorCache;
import com.huawei.finance.front.one.application.integration.conversation.ChatReadCursorRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 聊天事件消费游标应用服务。
 *
 * <p>WebSocket ack 会调用该服务记录用户已经消费到的最大 seq。该游标适合展示消费进度、
 * 辅助诊断以及非 active 场景减少重复；恢复正在输出的 active run 时，新渲染实例仍应
 * 打开 run 级事件恢复，从 run 的 firstSeq 之前补发并持续接续到终态，避免把另一台设备的
 * 已消费位置误当成当前页面的展示位置。</p>
 */
@Service
public class ChatReadCursorApplicationService {
    private final ChatReadCursorRepository repository;
    private final ChatReadCursorCache cache;
    private final PermissionChecker permissionChecker;
    private final SessionRepository sessionRepository;
    private final ChatReadCursorProperties properties;
    private final ConcurrentMap<String, Instant> lastDatabaseFlushAt = new ConcurrentHashMap<>();

    public ChatReadCursorApplicationService(ChatReadCursorRepository repository, ChatReadCursorCache cache,
                                            PermissionChecker permissionChecker, SessionRepository sessionRepository,
                                            ChatReadCursorProperties properties) {
        this.repository = repository;
        this.cache = cache;
        this.permissionChecker = permissionChecker;
        this.sessionRepository = sessionRepository;
        this.properties = properties;
    }

    /**
     * 读取当前用户在指定会话中的最大已消费事件序号。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 前端聊天会话标识。
     * @return 当前用户已消费的最大 seq；没有游标时返回 0。
     */
    public long findLastConsumedSeq(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        ensureOwnedSession(user, sessionId);
        Optional<ChatReadCursor> cached = cache.find(user.tenantId(), user.userId(), sessionId);
        if (cached.isPresent()) {
            return cached.get().lastConsumedSeq();
        }
        Optional<ChatReadCursor> persisted = repository.find(user.tenantId(), user.userId(), sessionId);
        persisted.ifPresent(cache::put);
        return persisted.map(ChatReadCursor::lastConsumedSeq).orElse(0L);
    }

    /**
     * 记录 WebSocket ack，并按配置节流写入 openGauss。
     *
     * @param user WebSocket 握手解析出的不可变用户身份快照。
     * @param sessionId run 所属聊天会话标识；调用方必须已经校验 run 归属。
     * @param seq 客户端已处理完成的最大事件序号。
     */
    public void acknowledgeTrustedSession(UserContext user, String sessionId, long seq) {
        if (seq <= 0 || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChatReadCursor cursor = new ChatReadCursor(null, user.tenantId(), user.userId(), sessionId, seq, Instant.now());
        cache.put(cursor);
        if (shouldFlushToDatabase(user, sessionId)) {
            repository.upsert(user.tenantId(), user.userId(), sessionId, seq);
        }
    }

    /**
     * 强制把最后 ack 位置写入 openGauss，通常用于连接关闭前的 best-effort flush。
     *
     * @param user WebSocket 握手解析出的不可变用户身份快照。
     * @param sessionId run 所属聊天会话标识；调用方必须已经校验 run 归属。
     * @param seq 客户端已处理完成的最大事件序号。
     */
    public void flushTrustedSession(UserContext user, String sessionId, long seq) {
        if (seq <= 0 || sessionId == null || sessionId.isBlank()) {
            return;
        }
        ChatReadCursor cursor = repository.upsert(user.tenantId(), user.userId(), sessionId, seq);
        cache.put(cursor);
        lastDatabaseFlushAt.put(flushKey(user.tenantId(), user.userId(), sessionId), Instant.now());
    }

    private boolean shouldFlushToDatabase(UserContext user, String sessionId) {
        Duration interval = properties.getDatabaseFlushInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            return true;
        }
        Instant now = Instant.now();
        String key = flushKey(user.tenantId(), user.userId(), sessionId);
        Instant previous = lastDatabaseFlushAt.get(key);
        if (previous != null && previous.plus(interval).isAfter(now)) {
            return false;
        }
        lastDatabaseFlushAt.put(key, now);
        return true;
    }

    private void ensureOwnedSession(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("会话 ID 不能为空");
        }
        Optional<ChatSession> session = sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId);
        if (session.isEmpty()) {
            throw new SecurityException("会话不存在或不属于当前用户");
        }
    }

    private String flushKey(String tenantId, String userId, String sessionId) {
        return tenantId + ":" + userId + ":" + sessionId;
    }
}
