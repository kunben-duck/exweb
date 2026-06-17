package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatRunMode;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.util.Map;

/**
 * 创建 ChatRun 的应用层上下文。
 *
 * <p>run 创建需要同时携带用户、路由、RuntimeBinding 和消息树挂接信息；使用命名上下文
 * 避免长散参签名，也让调用方明确这些字段属于同一次 run 的不可变快照。</p>
 *
 * @param runId 本轮 run ID，由入口编排在写用户消息前生成。
 * @param user 请求入口解析出的不可变用户身份。
 * @param sessionId 本轮 run 所属会话。
 * @param route 本轮最终路由结果。
 * @param binding 命中的 RuntimeBinding；仅 AgentRuntime 多轮续接场景存在。
 * @param metadata 前端 run metadata 的安全副本，不应包含 Cookie 等敏感请求头。
 * @param runMode 消息树运行模式。
 * @param parentMessageId 本轮消息树挂接父节点。
 * @param userMessageId 本轮用户消息节点 ID。
 */
public record CreateChatRunContext(
        String runId,
        UserContext user,
        String sessionId,
        RouteTarget route,
        RuntimeBinding binding,
        Map<String, Object> metadata,
        ChatRunMode runMode,
        String parentMessageId,
        String userMessageId
) {
    public Map<String, Object> safeMetadata() {
        return metadata == null ? Map.of() : metadata;
    }
}
