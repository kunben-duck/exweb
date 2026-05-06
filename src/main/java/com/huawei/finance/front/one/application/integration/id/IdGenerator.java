package com.huawei.finance.front.one.application.integration.id;

/**
 * ID 生成端口。
 *
 * <p>应用层只声明需要哪类业务 ID；具体格式、算法、号段或企业统一 ID 服务由基础设施层实现。</p>
 */
public interface IdGenerator {
    String newId(String bizType, IdGenerateContext context);

    default String newId(String bizType) {
        return newId(bizType, IdGenerateContext.empty());
    }
}
