package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_chat_message_t 与 fin_ex_chat_message_attachment_t 的 MyBatis Mapper。
 *
 * <p>消息表按树结构存储：{@code parent_message_id} 是树父节点，
 * {@code node_order} 是会话内创建顺序。前端默认历史查询应读取当前 active path，
 * 而不是简单返回全量节点。</p>
 */
@Mapper
public interface ChatMessageMapper {
    @Insert("""
            INSERT INTO fin_ex_chat_message_t(
                id, tenant_id, user_id, session_id, parent_message_id, node_order, tree_depth,
                sibling_index, role, content, token_count, run_id, origin_type, locked,
                source_session_id, source_message_id, edited_from_message_id,
                regenerated_from_message_id, metadata_json, created_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{parentMessageId}, #{nodeOrder}, #{treeDepth},
                #{siblingIndex}, #{role}, #{content}, #{tokenCount}, #{runId}, #{originType}, #{locked},
                #{sourceSessionId}, #{sourceMessageId}, #{editedFromMessageId},
                #{regeneratedFromMessageId}, #{metadataJson}, #{createdAt}
            )
            """)
    void insert(ChatMessageRow row);

    @Insert("""
            INSERT INTO fin_ex_chat_message_part_t(
                id, tenant_id, user_id, session_id, message_id, run_id, part_type,
                source_type, content_text, title, status, channel, display_hint, visible,
                payload_json, part_order, created_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{messageId}, #{runId}, #{partType},
                #{sourceType}, #{contentText}, #{title}, #{status}, #{channel}, #{displayHint}, #{visible},
                #{payloadJson}, #{partOrder}, #{createdAt}
            )
            """)
    void insertPart(ChatMessagePartRow row);

    @Insert("""
            INSERT INTO fin_ex_chat_message_attachment_t(
                id, tenant_id, user_id, session_id, message_id, document_id, attachment_order,
                name, content_type, size_bytes, source_attachment_id, created_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{messageId}, #{documentId}, #{attachmentOrder},
                #{name}, #{contentType}, #{sizeBytes}, #{sourceAttachmentId}, #{createdAt}
            )
            """)
    void insertAttachment(ChatMessageAttachmentRow row);

    @Select("""
            WITH RECURSIVE active_path AS (
                SELECT m.*
                FROM fin_ex_chat_message_t m
                JOIN fin_ex_chat_session_t s ON s.id = m.session_id
                WHERE m.tenant_id = #{tenantId}
                  AND m.user_id = #{userId}
                  AND m.session_id = #{sessionId}
                  AND m.id = COALESCE(#{leafMessageId}, s.current_leaf_message_id)
                UNION ALL
                SELECT p.*
                FROM fin_ex_chat_message_t p
                JOIN active_path child ON child.parent_message_id = p.id
                WHERE p.tenant_id = #{tenantId}
                  AND p.user_id = #{userId}
                  AND p.session_id = #{sessionId}
            )
            SELECT * FROM active_path
            ORDER BY tree_depth DESC, node_order DESC
            LIMIT #{limit}
            """)
    @Results(id = "chatMessageResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "node_order", property = "nodeOrder"),
            @Result(column = "tree_depth", property = "treeDepth"),
            @Result(column = "sibling_index", property = "siblingIndex"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "origin_type", property = "originType"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "edited_from_message_id", property = "editedFromMessageId"),
            @Result(column = "regenerated_from_message_id", property = "regeneratedFromMessageId"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findRecentActivePath(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("leafMessageId") String leafMessageId,
            @Param("limit") int limit
    );

    @Select("""
            WITH RECURSIVE active_path AS (
                SELECT m.*
                FROM fin_ex_chat_message_t m
                JOIN fin_ex_chat_session_t s ON s.id = m.session_id
                WHERE m.tenant_id = #{tenantId}
                  AND m.user_id = #{userId}
                  AND m.session_id = #{sessionId}
                  AND m.id = COALESCE(#{leafMessageId}, s.current_leaf_message_id)
                UNION ALL
                SELECT p.*
                FROM fin_ex_chat_message_t p
                JOIN active_path child ON child.parent_message_id = p.id
                WHERE p.tenant_id = #{tenantId}
                  AND p.user_id = #{userId}
                  AND p.session_id = #{sessionId}
            )
            SELECT * FROM active_path
            ORDER BY tree_depth ASC, node_order ASC
            """)
    @Results(id = "chatMessagePathResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "node_order", property = "nodeOrder"),
            @Result(column = "tree_depth", property = "treeDepth"),
            @Result(column = "sibling_index", property = "siblingIndex"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "origin_type", property = "originType"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "edited_from_message_id", property = "editedFromMessageId"),
            @Result(column = "regenerated_from_message_id", property = "regeneratedFromMessageId"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findActivePath(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("leafMessageId") String leafMessageId
    );

    @Select("""
            SELECT *
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND role IN ('user', 'assistant')
            ORDER BY node_order ASC, created_at ASC, id ASC
            """)
    @ResultMap("chatMessagePathResultMap")
    List<ChatMessageRow> findAllBySession(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId
    );

    @Select("""
            <script>
            SELECT *
            FROM (
                SELECT m.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY m.session_id
                           ORDER BY m.node_order ASC, m.created_at ASC, m.id ASC
                       ) AS rn
                FROM fin_ex_chat_message_t m
                JOIN fin_ex_chat_session_t s
                  ON s.id = m.session_id
                 AND s.tenant_id = m.tenant_id
                 AND s.user_id = m.user_id
                WHERE m.tenant_id = #{tenantId}
                  AND m.user_id = #{userId}
                  AND m.role = 'assistant'
                  AND s.status &lt;&gt; 'DELETED'
                  AND m.session_id IN
                  <foreach collection="sessionIds" item="sessionId" open="(" separator="," close=")">
                      #{sessionId}
                  </foreach>
            ) ranked
            WHERE ranked.rn = 1
            </script>
            """)
    @Results(id = "chatMessageFirstAssistantResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "node_order", property = "nodeOrder"),
            @Result(column = "tree_depth", property = "treeDepth"),
            @Result(column = "sibling_index", property = "siblingIndex"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "origin_type", property = "originType"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "edited_from_message_id", property = "editedFromMessageId"),
            @Result(column = "regenerated_from_message_id", property = "regeneratedFromMessageId"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findFirstAssistantBySessions(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionIds") List<String> sessionIds
    );

    @Select("""
            SELECT *
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{messageId}
            """)
    @Results(id = "chatMessageByIdResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "node_order", property = "nodeOrder"),
            @Result(column = "tree_depth", property = "treeDepth"),
            @Result(column = "sibling_index", property = "siblingIndex"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "origin_type", property = "originType"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "edited_from_message_id", property = "editedFromMessageId"),
            @Result(column = "regenerated_from_message_id", property = "regeneratedFromMessageId"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    Optional<ChatMessageRow> findByOwnerAndId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    @Select("""
            <script>
            SELECT *
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND role = #{role}
              <choose>
                <when test="parentMessageId == null">
              AND parent_message_id IS NULL
                </when>
                <otherwise>
              AND parent_message_id = #{parentMessageId}
                </otherwise>
              </choose>
            ORDER BY sibling_index ASC, node_order ASC
            </script>
            """)
    @Results(id = "chatMessageVariantsResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "parent_message_id", property = "parentMessageId"),
            @Result(column = "node_order", property = "nodeOrder"),
            @Result(column = "tree_depth", property = "treeDepth"),
            @Result(column = "sibling_index", property = "siblingIndex"),
            @Result(column = "token_count", property = "tokenCount"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "origin_type", property = "originType"),
            @Result(column = "source_session_id", property = "sourceSessionId"),
            @Result(column = "source_message_id", property = "sourceMessageId"),
            @Result(column = "edited_from_message_id", property = "editedFromMessageId"),
            @Result(column = "regenerated_from_message_id", property = "regeneratedFromMessageId"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageRow> findSiblings(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("parentMessageId") String parentMessageId,
            @Param("role") String role
    );

    @Select("""
            <script>
            SELECT COUNT(1)
            FROM fin_ex_chat_message_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND role = #{role}
              <choose>
                <when test="parentMessageId == null">
              AND parent_message_id IS NULL
                </when>
                <otherwise>
              AND parent_message_id = #{parentMessageId}
                </otherwise>
              </choose>
            </script>
            """)
    int countSiblings(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("parentMessageId") String parentMessageId,
            @Param("role") String role
    );

    @Select("""
            SELECT *
            FROM fin_ex_chat_message_attachment_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND message_id = #{messageId}
            ORDER BY attachment_order ASC
            """)
    @Results(id = "chatMessageAttachmentResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "document_id", property = "documentId"),
            @Result(column = "attachment_order", property = "attachmentOrder"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "size_bytes", property = "sizeBytes"),
            @Result(column = "source_attachment_id", property = "sourceAttachmentId"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessageAttachmentRow> findAttachmentsByMessage(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("messageId") String messageId
    );

    @Select("""
            <script>
            SELECT *
            FROM fin_ex_chat_message_part_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND session_id = #{sessionId}
              AND message_id IN
              <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                  #{messageId}
              </foreach>
            ORDER BY message_id ASC, part_order ASC, created_at ASC, id ASC
            </script>
            """)
    @Results(id = "chatMessagePartResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "message_id", property = "messageId"),
            @Result(column = "run_id", property = "runId"),
            @Result(column = "part_type", property = "partType"),
            @Result(column = "source_type", property = "sourceType"),
            @Result(column = "content_text", property = "contentText"),
            @Result(column = "title", property = "title"),
            @Result(column = "status", property = "status"),
            @Result(column = "channel", property = "channel"),
            @Result(column = "display_hint", property = "displayHint"),
            @Result(column = "visible", property = "visible"),
            @Result(column = "payload_json", property = "payloadJson"),
            @Result(column = "part_order", property = "partOrder"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<ChatMessagePartRow> findPartsByMessages(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageIds") List<String> messageIds
    );
}
