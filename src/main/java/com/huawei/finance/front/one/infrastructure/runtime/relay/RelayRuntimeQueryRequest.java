package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import java.util.List;
import java.util.Map;

/**
 * Relay Runtime 查询接口的出站 wire DTO。
 *
 * <p>该类型只表达 FinanceEXChatService 与 RelayAgentRuntime 之间的下游协议，不等同于
 * application 层的 AgentRuntimeRequest。这样可以避免把 tenant/user、意图结果、记忆上下文、
 * 请求头快照等内部编排细节直接暴露给下游 Runtime。</p>
 *
 * @param runId 本轮 ChatService run 标识，用于下游日志追踪和 stop 关联。
 * @param sessionId 前端会话标识，用于下游建立或续接自己的会话上下文。
 * @param runtimeSessionId RelayAgentRuntime 自己的会话标识，首次调用可为空。
 * @param query 本轮用户输入。
 * @param attachments 本轮用户显式引用的文档附件。
 * @param metadata 允许透传给 Relay 的非敏感扩展信息；不得包含 Cookie、token 或内部上下文对象。
 */
public record RelayRuntimeQueryRequest(
        String runId,
        String sessionId,
        String runtimeSessionId,
        String query,
        List<AttachmentRef> attachments,
        Map<String, Object> metadata
) {
    public RelayRuntimeQueryRequest {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
