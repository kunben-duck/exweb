package com.huawei.finance.front.one.application.integration.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.List;
import java.util.Map;

/**
 * 财经领域 DomainAgent 指定调用请求。
 *
 * @param user 当前用户身份快照。
 * @param sessionId ChatService 会话 ID。
 * @param runId ChatService run ID。
 * @param domainAgentId 前端显式选择的 DomainAgent ID。
 * @param query 用户本轮输入。
 * @param documents 已校验归属和状态的文档库元数据。
 * @param metadata run metadata；DomainAgent chat 下游请求体直接使用该对象。
 * @param forwardHeaders 请求入口捕获的 Cookie 等转发头快照；仅用于出站请求头，不能进入请求体或持久化数据。
 */
public record DomainAgentRequest(
        UserContext user,
        String sessionId,
        String runId,
        String domainAgentId,
        String query,
        List<UploadedDocument> documents,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public DomainAgentRequest {
        documents = documents == null ? List.of() : List.copyOf(documents);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
    }
}
