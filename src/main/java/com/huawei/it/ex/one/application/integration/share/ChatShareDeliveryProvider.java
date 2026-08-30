/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.share;

/**
 * 分享发送 provider 防腐层。
 *
 * <p>应用层只理解“把分享链接发送到某个 provider”；WeLink、邮件或企业内部消息 SDK 的
 * wire 协议都收敛在具体 provider 实现中。</p>
 */
public interface ChatShareDeliveryProvider {
    /**
     * provider 编码。
     *
     * @return 发送 provider 编码，例如 welink。
     */
    String providerCode();

    /**
     * 发送分享链接。
     *
     * @param request 已完成字段归一化的 provider 发送请求。
     * @return provider 调用结果；失败时不应抛出业务异常，除非 provider 未启用或配置缺失。
     */
    ChatShareProviderDeliveryResult deliver(ChatShareProviderDeliveryRequest request);
}
