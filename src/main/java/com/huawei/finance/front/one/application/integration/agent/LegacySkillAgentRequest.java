package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.List;
import java.util.Map;

/**
 * 老 Agent 指定技能调用请求。
 *
 * @param user 当前用户身份快照。
 * @param sessionId ChatService 会话 ID。
 * @param runId ChatService run ID。
 * @param skillId 前端显式选择的技能 ID。
 * @param query 用户本轮输入。
 * @param documents 已校验归属和状态的文档库元数据。
 * @param metadata run metadata，用于读取 legacyAgent 参数。
 */
public record LegacySkillAgentRequest(
        UserContext user,
        String sessionId,
        String runId,
        String skillId,
        String query,
        List<UploadedDocument> documents,
        Map<String, Object> metadata
) {
    public LegacySkillAgentRequest {
        documents = documents == null ? List.of() : List.copyOf(documents);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
