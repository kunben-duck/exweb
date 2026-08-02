package com.huawei.it.ex.one.application.integration.conversation;

/**
 * 会话列表可选过滤条件。
 *
 * @param appId 应用标识精确过滤条件；为空表示不过滤。
 * @param title 会话标题包含过滤条件；为空表示不过滤。
 */
public record SessionListFilter(
        String appId,
        String title
) {
    public static SessionListFilter empty() {
        return new SessionListFilter(null, null);
    }
}
