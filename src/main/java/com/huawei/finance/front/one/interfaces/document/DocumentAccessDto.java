package com.huawei.finance.front.one.interfaces.document;

import java.time.Instant;

/**
 * 前端文档访问地址 DTO。
 *
 * @param documentId 文档标识。
 * @param accessUrl 后端受控下载或预览地址。
 * @param accessType 访问方式，首版为 BACKEND_STREAM。
 * @param expiresAt 地址过期时间；受控后端流可为空。
 */
public record DocumentAccessDto(
        String documentId,
        String accessUrl,
        String accessType,
        Instant expiresAt
) {}
