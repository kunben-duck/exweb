/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.ConversationMemoryMessage;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

/**
 * 财经领域 DomainAgent 指定调用请求。
 *
 * @param user 当前用户身份快照。
 * @param sessionId ChatService 会话 ID。
 * @param runId ChatService run ID。
 * @param domainAgentId 当前绑定的 DomainAgent ID。
 * @param runtimeSessionId DomainAgent 下游会话 ID；为空时使用 ChatService sessionId。
 * @param query 用户本轮输入。
 * @param messageId 本轮输入对应的 ChatService user 消息 ID；为空时保持旧请求兼容。
 * @param documents 已校验归属和状态的文档库元数据。
 * @param messages 服务端组装的短期对话上下文。
 * @param shortTermMemoryEnabled 是否启用了短期记忆；启用但没有历史时仍发送空数组。
 * @param metadata run metadata；作为 DomainAgent body 的业务扩展，不能覆盖 skillId/query/sessionId。
 * @param forwardHeaders 请求入口捕获的 Cookie 等转发头快照；仅用于出站请求头，不能进入请求体或持久化数据。
 */
public record DomainAgentRequest(
        UserContext user,
        String sessionId,
        String runId,
        String domainAgentId,
        String runtimeSessionId,
        String query,
        String messageId,
        List<UploadedDocument> documents,
        List<ConversationMemoryMessage> messages,
        boolean shortTermMemoryEnabled,
        Map<String, Object> metadata,
        @JsonIgnore RuntimeForwardHeaders forwardHeaders
) {
    public DomainAgentRequest {
        documents = documents == null ? List.of() : List.copyOf(documents);
        messages = messages == null ? List.of() : List.copyOf(messages);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        forwardHeaders = forwardHeaders == null ? RuntimeForwardHeaders.empty() : forwardHeaders;
        messageId = messageId == null || messageId.isBlank() ? null : messageId.trim();
    }

    public DomainAgentRequest(
            UserContext user,
            String sessionId,
            String runId,
            String domainAgentId,
            String runtimeSessionId,
            String query,
            List<UploadedDocument> documents,
            List<ConversationMemoryMessage> messages,
            boolean shortTermMemoryEnabled,
            Map<String, Object> metadata,
            RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, domainAgentId, runtimeSessionId, query, null, documents,
                messages, shortTermMemoryEnabled, metadata, forwardHeaders);
    }

    public DomainAgentRequest(
            UserContext user,
            String sessionId,
            String runId,
            String domainAgentId,
            String runtimeSessionId,
            String query,
            String messageId,
            List<UploadedDocument> documents,
            Map<String, Object> metadata,
            RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, domainAgentId, runtimeSessionId, query, messageId, documents,
                List.of(), false, metadata, forwardHeaders);
    }

    public DomainAgentRequest(
            UserContext user,
            String sessionId,
            String runId,
            String domainAgentId,
            String runtimeSessionId,
            String query,
            List<UploadedDocument> documents,
            Map<String, Object> metadata,
            RuntimeForwardHeaders forwardHeaders) {
        this(user, sessionId, runId, domainAgentId, runtimeSessionId, query, null, documents,
                List.of(), false, metadata, forwardHeaders);
    }
}
