package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionNumberPage;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.util.List;
import java.util.Map;

/**
 * 会话应用门面。
 *
 * <p>接口层通过该门面管理当前用户可见的聊天会话；用户身份必须由 Controller 在请求入口解析后
 * 作为不可变 {@link UserContext} 显式传入，应用层不再读取 ThreadLocal 或 HTTP 上下文。</p>
 */
public interface ChatSessionFacade {
    /**
     * 创建当前用户的新聊天会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param title 会话标题；为空时由应用层使用默认标题。
     * @param channel 会话来源渠道；为空时默认为 web。
     * @return 新建会话元数据。
     */
    ChatSession createSession(UserContext user, String title, String channel);

    /**
     * 创建带可选 App Tag 的会话。
     */
    default ChatSession createSession(UserContext user, String title, String channel, String appId, String appName) {
        if ((appId != null && !appId.isBlank()) || (appName != null && !appName.isBlank())) {
            throw new UnsupportedOperationException("当前会话实现不支持 App Tag");
        }
        return createSession(user, title, channel);
    }

    /**
     * 查询当前用户可见的单个会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 当前用户拥有的会话元数据。
     */
    ChatSession getSession(UserContext user, String sessionId);

    /**
     * 查询当前用户的会话列表。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 会话分页结果。
     */
    ChatSessionPage listSessions(UserContext user, String cursor, int limit);

    /** 按可选 appId 查询当前用户的会话列表。 */
    default ChatSessionPage listSessions(UserContext user, String appId, String cursor, int limit) {
        if (appId != null && !appId.isBlank()) {
            throw new UnsupportedOperationException("当前会话实现不支持 appId 过滤");
        }
        return listSessions(user, cursor, limit);
    }

    /**
     * 按页码查询当前用户的会话列表。
     *
     * <p>该方法服务传统分页 UI，返回 totalRows/totalPages；旧游标分页接口继续使用
     * {@link #listSessions(UserContext, String, int)}。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param curPage 当前页码，从 1 开始。
     * @param pageSize 每页条数。
     * @return 页码分页结果。
     */
    default ChatSessionNumberPage listSessionsByPage(UserContext user, int curPage, int pageSize) {
        int normalizedPage = Math.max(1, curPage);
        int normalizedSize = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        ChatSessionPage page = listSessions(user, null, normalizedSize);
        long totalRows = page.items().size();
        long totalPages = totalRows == 0 ? 0 : 1;
        return new ChatSessionNumberPage(page.items(), normalizedPage, normalizedSize, totalRows, totalPages);
    }

    /** 按可选 appId 执行页码分页查询。 */
    default ChatSessionNumberPage listSessionsByPage(UserContext user, String appId, int curPage, int pageSize) {
        if (appId != null && !appId.isBlank()) {
            throw new UnsupportedOperationException("当前会话实现不支持 appId 过滤");
        }
        return listSessionsByPage(user, curPage, pageSize);
    }

    /**
     * 批量查询会话首条 assistant 回答。
     *
     * <p>该能力专门服务会话分页列表摘要展示，避免 Controller 对每个会话逐个查历史消息造成 N+1 查询。
     * 返回 Map 的 key 为 sessionId，value 为该会话第一条完整 assistant 消息正文。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessions 当前分页页内会话快照，必须已通过 owner 查询得到。
     * @return sessionId 到首条 assistant 回答的映射；没有 assistant 回复的会话不会出现在 Map 中。
     */
    Map<String, String> findFirstAssistantAnswers(UserContext user, List<ChatSession> sessions);

    /**
     * 查询当前用户可见会话的历史消息。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 按创建时间正序排列的历史消息分页。
     */
    ChatMessagePage listMessages(UserContext user, String sessionId, String cursor, int limit);

    /**
     * 查询当前用户可见会话某条 leaf 对应的 active path。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @param leafMessageId 指定 leaf；为空时使用会话当前 leaf。
     * @param cursor 保留分页游标；当前 active path 查询返回空 nextCursor。
     * @param limit 最大返回条数。
     * @return root 到 leaf 的可见消息路径。
     */
    default ChatMessagePage listMessages(UserContext user, String sessionId, String leafMessageId, String cursor, int limit) {
        return listMessages(user, sessionId, cursor, limit);
    }

    /**
     * 查询当前用户可见会话的完整消息树。
     *
     * <p>该方法只返回业务可见的 user/assistant 完整消息，不返回隐藏 system 或内部工具原始节点。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 当前会话内按 nodeOrder 排序的可见消息节点。
     */
    default List<ChatMessage> listMessageTree(UserContext user, String sessionId) {
        return List.of();
    }

    /**
     * 查询当前用户可见会话的轻量消息树节点。
     *
     * <p>该方法用于历史消息接口装配版本摘要，不要求返回 assistant parts。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 当前会话内按 nodeOrder 排序的可见消息节点。
     */
    default List<ChatMessage> listMessageTreeNodes(UserContext user, String sessionId) {
        return listMessageTree(user, sessionId);
    }

    /**
     * 重命名当前用户可见的会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @param title 新标题；为空时保留原标题。
     * @return 更新后的会话元数据。
     */
    ChatSession renameSession(UserContext user, String sessionId, String title);

    /**
     * 归档当前用户可见的会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 归档后的会话元数据。
     */
    ChatSession archiveSession(UserContext user, String sessionId);

    /**
     * 恢复当前用户可见的归档会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 恢复后的会话元数据。
     */
    ChatSession restoreSession(UserContext user, String sessionId);

    /**
     * 软删除当前用户可见的会话。
     *
     * <p>删除只把会话状态置为 {@code DELETED}，不会物理删除消息、run、event、反馈或附件引用。
     * 这样可以保持审计和故障排查事实完整，同时让前端列表和详情不再看到该会话。
     * 如果会话仍有 active run，应用层会先主动取消该 run，再执行软删除。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 删除后的会话元数据。
     */
    ChatSession deleteSession(UserContext user, String sessionId);

    /**
     * 批量软删除当前用户可见的会话。
     *
     * <p>删除语义为 all-or-nothing：所有会话都会先完成归属校验；
     * 任意一个会话不可删除时，本次请求整体失败，不做部分删除。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionIds 待软删除会话 ID 列表。
     * @return 删除后的会话快照列表。
     */
    List<ChatSession> deleteSessions(UserContext user, List<String> sessionIds);

    /**
     * 查询指定消息的同父同角色候选版本。
     */
    default java.util.List<ChatMessage> listVariants(UserContext user, String sessionId, String messageId) {
        return java.util.List.of();
    }

    /**
     * 切换当前会话 active path 到指定叶子。
     */
    default ChatSession selectPath(UserContext user, String sessionId, String leafMessageId) {
        return getSession(user, sessionId);
    }

    /**
     * 从某条消息创建只读历史快照分支会话。
     */
    default ChatSession createBranch(UserContext user, String sessionId, String sourceMessageId, String title) {
        return getSession(user, sessionId);
    }
}
