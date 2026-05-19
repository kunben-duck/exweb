package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;

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
     * 关闭当前用户可见的会话。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @return 关闭后的会话元数据。
     */
    ChatSession closeSession(UserContext user, String sessionId);

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
