package com.huawei.finance.front.one.domain.task;

/**
 * SubAgent 或 SuperAgent 推断出的用户待补充信息。
 *
 * @param name 参数稳定名称，例如 invoiceNo、amount、invoiceImage。
 * @param description 给用户或模型看的补充说明。
 * @param type 参数类型，例如 string、number、document、image。
 * @param required 是否为完成任务必须提供的参数。
 */
public record RequiredInput(
        String name,
        String description,
        String type,
        boolean required
) {
    public RequiredInput {
        name = normalize(name);
        description = description == null ? "" : description;
        type = normalize(type);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
