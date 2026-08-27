package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.intent.IntentAccessNameResolver;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 将 ChatService 内部请求映射为意图服务 HTTP 请求体。
 *
 * <p>意图服务接口尚未形成稳定文档时，字段增删、命名调整和结构变化都应收敛在这个 mapper 中，
 * {@link FinEurekaIntentService} 不直接感知具体 wire body。</p>
 */
@Component
public class IntentServiceRequestMapper {
    private final IntentServiceHttpProperties properties;
    private final IntentAccessNameResolver accessNameResolver;

    public IntentServiceRequestMapper(IntentServiceHttpProperties properties) {
        this(properties, requested -> {
            if (requested != null && !requested.isBlank()) {
                return requested.trim();
            }
            String configured = properties.getAccessName();
            return configured == null ? "" : configured.trim();
        });
    }

    @Autowired
    public IntentServiceRequestMapper(IntentServiceHttpProperties properties,
                                      IntentAccessNameResolver accessNameResolver) {
        this.properties = properties;
        this.accessNameResolver = accessNameResolver;
    }

    /**
     * 构造下游意图识别请求体。
     *
     * @param command 本轮聊天命令。
     * @param memory 可选 SuperAgent 记忆上下文。
     * @param user 请求入口固化后的用户上下文。
     * @return 发送给意图服务的 HTTP 请求体。
     */
    public IntentRecognizeRequest toWireRequest(ChatCommand command, MemoryContext memory, UserContext user) {
        return toWireRequest(command, memory, user, null);
    }

    public IntentRecognizeRequest toWireRequest(ChatCommand command,
                                                MemoryContext memory,
                                                UserContext user,
                                                String userMessageId) {
        return toWireRequest(command, memory, user, userMessageId, List.of());
    }

    public IntentRecognizeRequest toWireRequest(
            ChatCommand command,
            MemoryContext memory,
            UserContext user,
            String userMessageId,
            List<IntentUserPreferenceCorrection> preferenceCorrections) {
        return new IntentRecognizeRequest(
                normalizeMessageId(userMessageId),
                accessNameResolver.resolve(command == null ? null : command.intentAccessName()),
                command == null ? "" : blankToDefault(command.message(), ""),
                intentUserId(user),
                preferenceCorrections == null ? List.of() : List.copyOf(preferenceCorrections),
                conversationContext(memory),
                Map.of("trace", properties.isTrace())
        );
    }

    private String normalizeMessageId(String messageId) {
        return messageId == null || messageId.isBlank() ? null : messageId.trim();
    }

    private String intentUserId(UserContext user) {
        if (user == null) {
            return "";
        }
        return firstText(user.employeeNumber(), user.userAccount(), user.ownerUserId(), user.userId());
    }

    private Map<String, Object> conversationContext(MemoryContext memory) {
        return memory == null || memory.routeMemory() == null
                ? com.huawei.it.ex.one.domain.memory.RouteMemoryContext.empty().toConversationContext()
                : memory.routeMemory().toConversationContext();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
