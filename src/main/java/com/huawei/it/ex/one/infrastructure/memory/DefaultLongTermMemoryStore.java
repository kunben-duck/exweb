package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认长期记忆适配实现。
 *
 * <p>长期记忆不是聊天主链路的必需能力。本实现作为默认安全适配器，
 * 在未接入真实长期记忆服务时不生成任何伪造记忆，也不吞入业务数据；后续可替换为对应 HTTP/gRPC/SDK 适配器。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.memory.long-term.provider", havingValue = "disabled", matchIfMissing = true)
public class DefaultLongTermMemoryStore implements LongTermMemoryStore {
    @Override
    public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) {
        return List.of();
    }

    @Override
    public void save(LongTermMemoryItem item) {
        // 长期记忆未启用时不写入外部系统。
    }
}
