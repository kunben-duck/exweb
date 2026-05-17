package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.memory.ChatFeedbackRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageFeedback;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 消息反馈应用服务。
 *
 * <p>反馈入口必须先校验被反馈消息属于当前用户，再写入独立反馈事实表。这样既避免前端伪造
 * messageId 越权写入，也避免反馈修改原始聊天消息造成审计口径混乱。</p>
 */
@Service
public class ChatFeedbackApplicationService {
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
        ChatMessage message = messageRepository.findByOwnerAndId(user.tenantId(), user.userId(), messageId)
                .orElseThrow(() -> new SecurityException("消息不存在或不属于当前用户"));
        if (!"assistant".equals(message.role())) {
            throw new IllegalArgumentException("只能反馈 assistant 消息");
        }
        String ownedRunId = blankToNull(runId);
        if (ownedRunId != null) {
            ChatRun run = chatRunService.requireOwnedRun(user, ownedRunId);
            if (!message.sessionId().equals(run.sessionId())) {
                throw new SecurityException("反馈 run 与消息不属于同一会话");
            }
        }
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
                blankToNull(reasonCode),
                blankToNull(commentText),
                metadata,
                now,
                now
        );
        return feedbackRepository.save(feedback);
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
}
