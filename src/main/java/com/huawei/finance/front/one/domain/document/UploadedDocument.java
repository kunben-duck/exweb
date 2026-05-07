package com.huawei.finance.front.one.domain.document;

import java.time.Instant;

/**
 * 已上传文档元数据。
 *
 * @param id 文档唯一标识。
 * @param tenantId 租户标识。
 * @param userId 上传用户标识。
 * @param sessionId 关联聊天会话标识，可为空。
 * @param originalName 用户上传时的原始文件名。
 * @param bucket 对象所在 bucket。
 * @param objectKey 对象存储 key。
 * @param contentType 文件 MIME 类型。
 * @param sizeBytes 文件大小，单位字节。
 * @param status 文档状态，例如 AVAILABLE、DELETED。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record UploadedDocument(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        String originalName,
        String bucket,
        String objectKey,
        String contentType,
        long sizeBytes,
        String status,
        Instant createdAt,
        Instant updatedAt
) {}
