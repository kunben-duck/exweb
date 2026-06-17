package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import org.springframework.stereotype.Component;

/**
 * 将 ChatService 内部请求映射为意图服务 HTTP 请求体。
 *
 * <p>意图服务接口尚未形成稳定文档时，字段增删、命名调整和结构变化都应收敛在这个 mapper 中，
 * {@link FinEurekaIntentService} 不直接感知具体 wire body。</p>
 */
@Component
public class IntentServiceRequestMapper {
    /**
     * 构造下游意图识别请求体。
     *
     * @param command 本轮聊天命令。
     * @param memory 可选 SuperAgent 记忆上下文。
     * @param user 请求入口固化后的用户上下文。
     * @return 发送给意图服务的 HTTP 请求体。
     */
    public IntentRecognizeRequest toWireRequest(ChatCommand command, MemoryContext memory, UserContext user) {
        return new IntentRecognizeRequest(
                user.tenantId(),
                user.userId(),
                command.sessionId(),
                command.message(),
                command.attachments(),
                command.metadata(),
                memory
        );
    }
}
