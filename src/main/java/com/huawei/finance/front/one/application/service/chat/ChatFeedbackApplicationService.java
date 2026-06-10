package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 消息反馈应用服务。
 *
 * <p>反馈入口必须先校验被反馈消息属于当前用户，再写入独立反馈事实表。这样既避免前端伪造
 * messageId 越权写入，也避免反馈修改原始聊天消息造成审计口径混乱。</p>
 */
@Service
public class ChatFeedbackApplicationService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final PermissionChecker permissionChecker;
    private final ChatMessageRepository messageRepository;
    private final ChatFeedbackRepository feedbackRepository;
    private final IdGenerator idGenerator;
    private final ChatRunApplicationService chatRunService;

    public ChatFeedbackApplicationService(PermissionChecker permissionChecker,
                                          ChatMessageRepository messageRepository,
                                          ChatFeedbackRepository feedbackRepository, IdGenerator idGenerator,
                                          ChatRunApplicationService chatRunService) {
        this.permissionChecker = permissionChecker;
        this.messageRepository = messageRepository;
        this.feedbackRepository = feedbackRepository;
        this.idGenerator = idGenerator;
        this.chatRunService = chatRunService;
    }

    /**
     * 保存当前用户对某条 assistant 消息的反馈。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param messageId 被反馈消息标识。
     * @param runId 反馈关联 run 标识，可为空。
     * @param rating 反馈评级，例如 LIKE、DISLIKE。
     * @param reasonCode 结构化原因编码，可为空。
     * @param commentText 用户补充说明，可为空。
     * @param metadata 前端扩展诊断信息。
     * @return 已保存的反馈事实。
     */
    public ChatMessageFeedback submit(UserContext user, String messageId, String runId, String rating, String reasonCode,
                                      String commentText, Map<String, Object> metadata) {
        permissionChecker.checkChatPermission(user);
        ChatMessage message = requireFeedbackTarget(user, messageId);
        String ownedRunId = blankToNull(runId);
        validateRunInMessageSession(user, message, ownedRunId);
        Instant now = Instant.now();
        String feedbackId = idGenerator.newId("feedback",
                IdGenerateContext.of(user.tenantId(), user.userId(), message.sessionId(), message.id()));
        ChatMessageFeedback feedback = new ChatMessageFeedback(
                feedbackId,
                user.tenantId(),
                user.userId(),
                message.sessionId(),
                message.id(),
                ownedRunId,
                normalizeRating(rating),
                STATUS_ACTIVE,
                blankToNull(reasonCode),
                blankToNull(commentText),
                metadata,
                now,
                now
        );
        return feedbackRepository.save(feedback);
    }

    /**
     * 取消当前用户对某条 assistant 消息的点赞或点踩。
     *
     * <p>取消仅清除“当前反馈状态”，不会删除聊天消息；如果此前没有反馈，也按幂等成功处理。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param messageId 被取消反馈的消息标识。
     * @param runId 可选 runId；存在时必须与消息处于同一会话。
     * @return 取消后的反馈状态。不存在历史反馈时返回一个不落库的 CANCELLED 结果。
     */
    public ChatMessageFeedback cancel(UserContext user, String messageId, String runId) {
        permissionChecker.checkChatPermission(user);
        ChatMessage message = requireFeedbackTarget(user, messageId);
        String ownedRunId = blankToNull(runId);
        validateRunInMessageSession(user, message, ownedRunId);
        Instant now = Instant.now();
        return feedbackRepository.cancel(user.tenantId(), user.userId(), message.id(), now)
                .orElseGet(() -> new ChatMessageFeedback(
                        null,
                        user.tenantId(),
                        user.userId(),
                        message.sessionId(),
                        message.id(),
                        ownedRunId,
                        null,
                        STATUS_CANCELLED,
                        null,
                        null,
                        Map.of(),
                        now,
                        now
                ));
    }

    /**
     * 批量查询当前用户对历史消息仍然有效的反馈。
     *
     * <p>该方法专门服务历史消息 DTO 装配，只查询 assistant 消息且只返回 ACTIVE 反馈，
     * 避免接口层逐条查询造成 N+1 数据库访问。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param sessionId 会话标识。
     * @param messages 当前页或当前版本候选消息。
     * @return key 为 messageId 的 ACTIVE 反馈。
     */
    public Map<String, ChatMessageFeedback> findActiveByMessages(
            UserContext user, String sessionId, Collection<ChatMessage> messages) {
        permissionChecker.checkChatPermission(user);
        if (messages == null || messages.isEmpty()) {
            return Map.of();
        }
        List<String> assistantMessageIds = messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .map(ChatMessage::id)
                .distinct()
                .toList();
        if (assistantMessageIds.isEmpty()) {
            return Map.of();
        }
        Set<String> allowedIds = Set.copyOf(assistantMessageIds);
        return feedbackRepository.findActiveByMessages(user.tenantId(), user.userId(), sessionId, assistantMessageIds)
                .entrySet()
                .stream()
                .filter(entry -> allowedIds.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String normalizeRating(String rating) {
        if (rating == null || rating.isBlank()) {
            throw new IllegalArgumentException("rating 不能为空");
        }
        String normalized = rating.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"LIKE".equals(normalized) && !"DISLIKE".equals(normalized)) {
            throw new IllegalArgumentException("rating 仅支持 LIKE 或 DISLIKE");
        }
        return normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ChatMessage requireFeedbackTarget(UserContext user, String messageId) {
        ChatMessage message = messageRepository.findByOwnerAndId(user.tenantId(), user.userId(), messageId)
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException("只能反馈 assistant 消息");
        }
        return message;
    }

    private void validateRunInMessageSession(UserContext user, ChatMessage message, String ownedRunId) {
        if (ownedRunId == null) {
            return;
        }
        ChatRun run = chatRunService.requireOwnedRun(user, ownedRunId);
        if (!message.sessionId().equals(run.sessionId())) {
            throw new SecurityException("反馈 run 与消息不属于同一会话");
        }
    }
}
