package com.huawei.it.ex.one.infrastructure.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

/**
 * fin_ex_chat_message_t、fin_ex_chat_message_part_t 与 fin_ex_chat_message_attachment_t 的 MyBatis Mapper。
 *
 * <p>SQL 统一维护在 {@code mapper/memory/ChatMessageMapper.opengauss.xml}，避免消息树递归查询和
 * variants 查询散落在 Java 注解中。</p>
 */
@Mapper
public interface ChatMessageMapper {
    /**
     * 写入一条 user 或 assistant 消息树节点。
     *
     * @param row 消息写入行，包含归属、父节点、节点序号、角色、内容和来源信息。
     */
    void insert(ChatMessageRow row);

    /**
     * 更新已有 assistant 消息正文和元数据。
     *
     * @param row 消息更新行，id/tenantId/userId/sessionId 定位消息，content/runId/metadataJson 为新值。
     * @return 影响行数。
     */
    int updateAssistant(ChatMessageRow row);

    /**
     * 批量写入 assistant 消息过程片段。
     *
     * @param rows part 写入行，包含消息归属、part 类型、展示语义、payload 和排序号。
     * @return 实际插入行数。
     */
    int insertParts(@Param("rows") List<ChatMessagePartRow> rows);

    /**
     * 写入消息与文档附件的引用关系。
     *
     * @param row 附件引用写入行，包含消息归属、documentId、展示快照和排序号。
     */
    void insertAttachment(ChatMessageAttachmentRow row);

    /**
     * 查询当前 active path 最近若干条消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 指定路径叶子；为空时使用会话 current leaf。
     * @param limit 最大返回条数。
     * @return 从 leaf 向前截取的消息列表。
     */
    List<ChatMessageRow> findRecentActivePath(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("leafMessageId") String leafMessageId,
            @Param("limit") int limit
    );

    /**
     * 查询从 root 到 leaf 的完整 active path。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 指定路径叶子；为空时使用会话 current leaf。
     * @return 按树深度正序排列的消息路径。
     */
    List<ChatMessageRow> findActivePath(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("leafMessageId") String leafMessageId
    );

    /**
     * 查询会话内全部可见 user/assistant 消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 按 node_order 正序排列的消息列表。
     */
    List<ChatMessageRow> findAllBySession(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId
    );

    /**
     * 批量查询每个会话的第一条 assistant 回答。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionIds 待装配摘要的会话 ID 列表。
     * @return 每个会话最多一条 assistant 消息。
     */
    List<ChatMessageRow> findFirstAssistantBySessions(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionIds") List<String> sessionIds
    );

    /**
     * 按 owner 边界查询单条消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 消息行。
     */
    Optional<ChatMessageRow> findByOwnerAndId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    /**
     * 按 owner、会话和消息 ID 集合批量查询消息节点。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 待查询消息 ID 集合。
     * @return 当前用户当前会话内命中的消息节点。
     */
    List<ChatMessageRow> findByOwnerSessionAndIds(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );

    /**
     * 查询同父节点、同角色下的候选版本。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param parentMessageId 父消息标识；为空表示根节点。
     * @param role 消息角色，通常为 user 或 assistant。
     * @return sibling 消息列表。
     */
    List<ChatMessageRow> findSiblings(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("parentMessageId") String parentMessageId,
            @Param("role") String role
    );

    /**
     * 统计同父节点、同角色下的候选版本数量。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param parentMessageId 父消息标识；为空表示根节点。
     * @param role 消息角色，通常为 user 或 assistant。
     * @return sibling 数量。
     */
    int countSiblings(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("parentMessageId") String parentMessageId,
            @Param("role") String role
    );

    /**
     * 查询单条消息关联的附件展示快照。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 附件引用列表。
     */
    List<ChatMessageAttachmentRow> findAttachmentsByMessage(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    /**
     * 批量查询一组消息关联的附件展示快照。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 待查询附件的消息 ID 列表。
     * @return 附件引用列表，按 messageId 和 attachmentOrder 排序。
     */
    List<ChatMessageAttachmentRow> findAttachmentsByMessages(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );

    /**
     * 批量查询一组消息的过程化 parts。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 待查询 parts 的消息 ID 列表。
     * @return parts 列表，按 messageId 和 partOrder 排序。
     */
    List<ChatMessagePartRow> findPartsByMessages(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );
}
