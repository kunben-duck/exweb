package com.huawei.it.ex.one.intent.application.repository;

import com.huawei.it.ex.one.intent.domain.memory.LongTermMemoryItem;
import java.util.List;

/**
 * 长期记忆检索端口。
 *
 * <p>当前上线版本默认禁用长期记忆，但保留端口以便后续接入向量库或企业知识记忆服务。</p>
 */
public interface LongTermMemoryStore {
    /**
     * 检索与当前 query 相关的长期记忆。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param query 当前用户输入或检索查询文本。
     * @param topK 最大返回条数。
     * @return 相关长期记忆条目。
     */
    List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK);

    /**
     * 保存长期记忆条目。
     *
     * @param item 长期记忆条目。
     */
    void save(LongTermMemoryItem item);
}
