/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.share;

import com.huawei.it.ex.one.domain.chat.ChatShareDelivery;

/**
 * 分享发送记录仓储端口。
 */
public interface ChatShareDeliveryRepository {
    /**
     * 保存分享发送审计记录。
     *
     * @param delivery 发送记录。
     * @return 已保存发送记录。
     */
    ChatShareDelivery save(ChatShareDelivery delivery);
}
