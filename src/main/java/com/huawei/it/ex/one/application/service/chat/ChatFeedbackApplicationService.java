package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;
import com.huawei.it.ex.one.domain.chat.ChatRun;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
     * @param command 反馈命令，包含消息、run、评级、原因和扩展诊断信息。
     * @return 已保存的反馈事实。
     */
    public ChatMessageFeedback submit(UserContext user, MessageFeedbackCommand command) {
        permissionChecker.checkChatPermission(user);
        ChatMessage message = requireFeedbackTarget(user, command.messageId());
        String ownedRunId = blankToNull(command.runId());
        validateRunInMessageSession(user, message, ownedRunId);
        Instant now = Instant.now();
        String feedbackId = idGenerator.newId("feedback",
                IdGenerateContext.of(user.tenantId(), user.ownerUserId(), message.sessionId(), message.id()));
        ChatMessageFeedback feedback = new ChatMessageFeedback(
                feedbackId,
                user.tenantId(),
                user.ownerUserId(),
                message.sessionId(),
                message.id(),
                ownedRunId,
                normalizeRating(command.rating()),
                STATUS_ACTIVE,
                blankToNull(command.reasonCode()),
                blankToNull(command.commentText()),
                command.metadata(),
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
        return feedbackRepository.cancel(user.tenantId(), user.ownerUserId(), message.id(), now)
                .orElseGet(() -> new ChatMessageFeedback(
                        null,
                        user.tenantId(),
                        user.ownerUserId(),
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
        return feedbackRepository.findActiveByMessages(user.tenantId(), user.ownerUserId(), sessionId, assistantMessageIds)
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
        String normalizedMessageId = normalizeMessageId(messageId);
        ChatMessage message = messageRepository.findByOwnerAndId(user.tenantId(), user.ownerUserId(), normalizedMessageId)
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException("只能反馈 assistant 消息");
        }
        return message;
    }

    private String normalizeMessageId(String messageId) {
        String normalized = blankToNull(messageId);
        if (normalized == null
                || "undefined".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)) {
            throw new IllegalArgumentException("assistant messageId 不能为空");
        }
        return normalized;
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
