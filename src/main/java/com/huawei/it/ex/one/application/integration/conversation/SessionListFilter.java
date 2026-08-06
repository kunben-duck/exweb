package com.huawei.it.ex.one.application.integration.conversation;

/**
 * 会话列表可选过滤条件。
 *
 * @param appId 应用标识精确过滤条件；为空表示不过滤。
 * @param title 会话标题包含过滤条件；为空表示不过滤。
 * @param channel 会话来源渠道精确过滤条件；为空表示不过滤。
 * @param appScope 会话 App 范围；为空表示保持现有全量语义。
 */
public record SessionListFilter(
        String appId,
        String title,
        String channel,
        SessionAppScope appScope
) {
    public SessionListFilter {
        if (appScope == SessionAppScope.MAIN_SITE && appId != null && !appId.isBlank()) {
            throw new IllegalArgumentException("appScope=MAIN_SITE 时不能同时指定 appId");
        }
    }

    public SessionListFilter(String appId, String title, String channel) {
        this(appId, title, channel, null);
    }

    public SessionListFilter(String appId, String title) {
        this(appId, title, null, null);
    }

    public static SessionListFilter empty() {
        return new SessionListFilter(null, null, null, null);
    }

    public boolean mainSiteOnly() {
        return appScope == SessionAppScope.MAIN_SITE;
    }
}
