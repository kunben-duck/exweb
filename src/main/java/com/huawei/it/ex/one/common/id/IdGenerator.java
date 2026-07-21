package com.huawei.it.ex.one.common.id;

/**
 * ID 生成端口。
 *
 * <p>应用层只声明需要哪类业务 ID；具体格式、算法、号段或企业统一 ID 服务由基础设施层实现。</p>
 */
public interface IdGenerator {
    /**
     * 生成指定业务类型的 ID。
     *
     * @param bizType 业务类型前缀，例如 session、run、msg、doc。
     * @param context ID 生成上下文，用于租户、用户、会话或 run 级隔离。
     * @return 新生成的业务 ID。
     */
    String newId(String bizType, IdGenerateContext context);

    /**
     * 生成无需上下文的指定业务类型 ID。
     *
     * @param bizType 业务类型前缀。
     * @return 新生成的业务 ID。
     */
    default String newId(String bizType) {
        return newId(bizType, IdGenerateContext.empty());
    }
}
