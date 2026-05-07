package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.finance.front.one.domain.memory.LongTermMemoryItem;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 关闭长期记忆时使用的生产实现。
 *
 * <p>长期记忆不是聊天主链路的必需能力。本实现用于明确表达“当前环境未启用长期记忆服务”，
 * 不生成任何伪造记忆，也不吞入业务数据；接入真实长期记忆服务时替换为对应 HTTP/gRPC/SDK 适配器。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.memory.long-term.provider", havingValue = "disabled", matchIfMissing = true)
public class DisabledLongTermMemoryStore implements LongTermMemoryStore {
    @Override
    public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) {
        return List.of();
    }

    @Override
    public void save(LongTermMemoryItem item) {
        // 长期记忆未启用时不写入外部系统。
    }
}
