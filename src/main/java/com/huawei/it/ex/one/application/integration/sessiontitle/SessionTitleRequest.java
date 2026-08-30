/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.sessiontitle;

import java.util.List;

/** 会话标题总结的中立请求模型。 */
public record SessionTitleRequest(
        String tenantId,
        String userId,
        String sessionId,
        List<String> queries,
        String language
) {
    public SessionTitleRequest {
        queries = queries == null ? List.of() : List.copyOf(queries);
    }
}
