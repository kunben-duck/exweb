package com.huawei.it.ex.one.infrastructure.id;

import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 默认 ID 生成器。
 *
 * <p>保留第一版 prefix + UUID 的实现方式；后续企业化规则可新增 implementor 替换该实现。</p>
 */
@Component
public class UuidIdGenerator implements IdGenerator {
    @Override
    public String newId(String bizType, IdGenerateContext context) {
        String prefix = bizType == null || bizType.isBlank() ? "id" : bizType.trim();
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
