/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.document.upload;

/**
 * 文档上传入口的请求上下文。
 *
 * <p>该对象只收敛接口层方法签名；Controller 负责把 multipart 字段绑定成应用层命令。</p>
 *
 * @param sessionId 可选会话标识。
 * @param metadataJson 前端上传扩展元数据 JSON。
 * @param cookieHeader 原始 Cookie 头，只作为内存快照传给允许透传的 provider，不持久化。
 */
public record DocumentUploadContext(
        String sessionId,
        String metadataJson,
        String cookieHeader
) {
}
