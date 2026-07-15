package com.huawei.it.ex.one.application.service.share;

import com.huawei.it.ex.one.application.config.ChatShareDeliveryProperties;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryProvider;
import com.huawei.it.ex.one.application.integration.share.ChatShareDeliveryRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryRequest;
import com.huawei.it.ex.one.application.integration.share.ChatShareProviderDeliveryResult;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatShareDelivery;
import com.huawei.it.ex.one.domain.chat.ChatShareUnavailableException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/**
 * 分享发送应用服务。
 *
 * <p>该服务只编排分享快照、发送权限、provider 防腐层和发送记录落库。
 * provider 调用失败会保存 FAILED 记录，但不会删除或撤销已创建的分享快照。</p>
 */
@Service
public class ChatShareDeliveryApplicationService {
    private final ChatShareRepository shareRepository;
    private final ChatShareDeliveryRepository deliveryRepository;
    private final ChatShareDeliveryProviderRegistry providerRegistry;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatShareAccessPolicy accessPolicy;
    private final ChatShareDeliveryProperties properties;
    private final Semaphore deliverySemaphore;

    public ChatShareDeliveryApplicationService(ChatShareRepository shareRepository,
                                               ChatShareDeliveryRepository deliveryRepository,
                                               ChatShareDeliveryProviderRegistry providerRegistry,
                                               IdGenerator idGenerator,
                                               PermissionChecker permissionChecker,
                                               ChatShareAccessPolicy accessPolicy,
                                               ChatShareDeliveryProperties properties) {
        this.shareRepository = shareRepository;
        this.deliveryRepository = deliveryRepository;
        this.providerRegistry = providerRegistry;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.deliverySemaphore = new Semaphore(properties.normalizedMaxConcurrency());
    }

    public ChatShareDelivery deliver(UserContext user, CreateChatShareDeliveryCommand command) {
        permissionChecker.checkChatPermission(user);
        CreateChatShareDeliveryCommand safeCommand = requireCommand(command);
        ChatShare share = loadShare(safeCommand.shareId());
        ensureDeliverable(user, share);
        DeliveryTargets targets = normalizeTargets(safeCommand.targetAccounts(), safeCommand.groupIds());
        String providerCode = normalizeProvider(safeCommand.provider());
        ChatShareDeliveryProvider provider = providerRegistry.requiredProvider(providerCode);
        String linkUrl = buildShareUrl(share.id());
        String title = chooseTitle(safeCommand.title(), share.title());
        String content = chooseContent(safeCommand.content(), share);
        ChatShareProviderDeliveryRequest providerRequest = new ChatShareProviderDeliveryRequest(
                share.tenantId(),
                providerUserAccount(user),
                title,
                linkUrl,
                content,
                String.join(",", targets.targetAccounts()),
                String.join(",", targets.groupIds()),
                blankToNull(safeCommand.language()),
                safeCommand.forwardHeaders()
        );
        Instant createdAt = Instant.now();
        ChatShareProviderDeliveryResult result = callProviderWithBulkhead(provider, providerRequest);
        Instant sentAt = Instant.now();
        ChatShareDelivery delivery = new ChatShareDelivery(
                idGenerator.newId("share_delivery", IdGenerateContext.of(
                        user.tenantId(), user.ownerUserId(), share.sourceSessionId(), share.id())),
                share.tenantId(),
                share.ownerUserId(),
                share.id(),
                providerCode,
                result.success() ? "SUCCESS" : "FAILED",
                targets.targetAccounts(),
                targets.groupIds(),
                title,
                content,
                blankToNull(safeCommand.language()),
                linkUrl,
                result.providerResponse(),
                result.errorCode(),
                result.errorMessage(),
                createdAt,
                sentAt,
                sentAt
        );
        return deliveryRepository.save(delivery);
    }

    private String providerUserAccount(UserContext user) {
        return user.userAccount() == null || user.userAccount().isBlank()
                ? user.ownerUserId()
                : user.userAccount().trim();
    }

    private CreateChatShareDeliveryCommand requireCommand(CreateChatShareDeliveryCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("分享发送请求不能为空");
        }
        return command;
    }

    private ChatShare loadShare(String shareId) {
        if (shareId == null || shareId.isBlank()) {
            throw new IllegalArgumentException("shareId 不能为空");
        }
        return shareRepository.findById(shareId)
                .orElseThrow(() -> new IllegalArgumentException("分享不存在: " + shareId));
    }

    private void ensureDeliverable(UserContext user, ChatShare share) {
        if (!accessPolicy.canDeliver(user, share)) {
            throw new SecurityException("无权发送该分享");
        }
        Instant now = Instant.now();
        if (share.revoked()) {
            throw new ChatShareUnavailableException("SHARE_REVOKED", "分享已撤销");
        }
        if (share.expired(now)) {
            throw new ChatShareUnavailableException("SHARE_EXPIRED", "分享已过期");
        }
    }

    private DeliveryTargets normalizeTargets(List<String> targetAccounts, List<String> groupIds) {
        List<String> normalizedAccounts = normalizeList(targetAccounts);
        List<String> normalizedGroups = normalizeList(groupIds);
        if (normalizedAccounts.isEmpty() && normalizedGroups.isEmpty()) {
            throw new IllegalArgumentException("targetAccounts 和 groupIds 至少需要一个非空目标");
        }
        int totalTargets = normalizedAccounts.size() + normalizedGroups.size();
        if (totalTargets > properties.normalizedMaxTargets()) {
            throw new IllegalArgumentException("分享发送目标数量超过限制: " + properties.normalizedMaxTargets());
        }
        return new DeliveryTargets(normalizedAccounts, normalizedGroups);
    }

    private List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String trimmed = blankToNull(value);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private String normalizeProvider(String provider) {
        String code = blankToNull(provider);
        if (code == null) {
            throw new IllegalArgumentException("provider 不能为空");
        }
        return code.toLowerCase(Locale.ROOT);
    }

    private String buildShareUrl(String shareId) {
        String prefix = properties.normalizedShareUrlPrefix();
        if (prefix.isBlank()) {
            throw new IllegalStateException("未配置 financeex.share.share-url-prefix");
        }
        return prefix + shareId;
    }

    private String chooseTitle(String requestTitle, String shareTitle) {
        String title = blankToNull(requestTitle);
        if (title == null) {
            title = blankToNull(shareTitle);
        }
        return title == null ? "问答分享" : truncate(singleLine(title), 120);
    }

    private String chooseContent(String requestContent, ChatShare share) {
        String content = blankToNull(requestContent);
        if (content == null && share.snapshot() != null && share.snapshot().answer() != null) {
            content = blankToNull(share.snapshot().answer().content());
        }
        if (content == null && share.snapshot() != null && share.snapshot().question() != null) {
            content = blankToNull(share.snapshot().question().content());
        }
        return truncate(singleLine(content == null ? "" : content), properties.normalizedContentMaxLength());
    }

    private ChatShareProviderDeliveryResult callProvider(ChatShareDeliveryProvider provider,
                                                        ChatShareProviderDeliveryRequest request) {
        try {
            return provider.deliver(request);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            return ChatShareProviderDeliveryResult.failed(
                    "PROVIDER_CALL_FAILED",
                    ex.getMessage(),
                    Map.of("exception", ex.getClass().getSimpleName())
            );
        }
    }

    private ChatShareProviderDeliveryResult callProviderWithBulkhead(ChatShareDeliveryProvider provider,
                                                                     ChatShareProviderDeliveryRequest request) {
        /*
         * 分享发送是用户可重试的外部 HTTP 调用。这里使用非阻塞 bulkhead，避免 WeLink 等 provider
         * 抖动时大量请求占满 boundedElastic 工作线程并反向影响聊天主链路。
         */
        if (!deliverySemaphore.tryAcquire()) {
            return ChatShareProviderDeliveryResult.failed(
                    "SHARE_DELIVERY_BUSY",
                    "分享发送并发已达上限，请稍后重试",
                    Map.of()
            );
        }
        try {
            return callProvider(provider, request);
        } finally {
            deliverySemaphore.release();
        }
    }

    private String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record DeliveryTargets(List<String> targetAccounts, List<String> groupIds) {}
}
