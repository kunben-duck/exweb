package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.gateway.LongTermMemoryStore;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 外部长期记忆服务的占位实现。
 *
 * <p>第一版先保留 mock 边界，不主动注入假记忆，避免影响 Agent 回答。
 * 后续对接真实外部服务时，直接在该实现内替换为 HTTP/gRPC/SDK 调用即可。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.memory.long-term.provider", havingValue = "mock-external", matchIfMissing = true)
public class MockExternalLongTermMemoryStore implements LongTermMemoryStore {
    @Override
    public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) {
        // mock 实现只验证调用链，不返回业务记忆；真实外部服务接入后在这里做向量/关键词召回。
        return List.of();
    }

    @Override
    public void save(LongTermMemoryItem item) {
        // 长期记忆写入是否由外部服务接收，后续根据真实服务契约补齐。
    }
}
