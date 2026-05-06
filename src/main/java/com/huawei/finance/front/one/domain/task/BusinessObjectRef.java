package com.huawei.finance.front.one.domain.task;

import java.util.Map;

/**
 * SubAgent 返回的业务对象引用。
 *
 * @param objectType 业务对象类型，例如 reimbursement、invoice、approval。
 * @param objectId 业务对象在下游系统中的稳定标识。
 * @param displayName 面向用户展示的对象名称。
 * @param attributes 附加属性，保存下游系统的非标准字段。
 */
public record BusinessObjectRef(
        String objectType,
        String objectId,
        String displayName,
        Map<String, Object> attributes
) {
    public BusinessObjectRef {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
