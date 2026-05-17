package com.huawei.finance.front.one.domain.document;

import java.time.Instant;

/**
 * 文档库资产元数据。
 *
 * <p>文档内容本身保存在对象存储中，本记录是聊天、检索、审计和文档库列表共同依赖的事实数据。
 * 用户在聊天中使用文档时，只传递 {@code id} 引用；应用层会回查该记录并补齐可信的文件名、MIME、
 * 大小和来源信息。</p>
 *
 * @param id 文档唯一标识。
 * @param tenantId 租户标识。
 * @param userId 文档所属用户标识。
 * @param sessionId 文档首次关联的聊天会话标识，可为空。
 * @param originalName 用户上传或导入时的原始文件名。
 * @param bucket 对象所在 bucket。
 * @param objectKey 对象存储 key。
 * @param contentType 文件 MIME 类型。
 * @param sizeBytes 文件大小，单位字节。
 * @param status 文档状态，例如 AVAILABLE、DELETED、FAILED。
 * @param source 文档来源，例如 LOCAL_UPLOAD、LIBRARY、CONNECTOR。
 * @param tokenSize 文档解析后的 token 数量，可为空表示尚未统计。
 * @param metadataJson 文档扩展元数据 JSON，用于保存来源追踪、解析诊断和后续检索信息。
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
        String source,
        Long tokenSize,
        String metadataJson,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * 判断文档是否可被聊天引用。
     *
     * @return 文档处于可用状态时返回 true。
     */
    public boolean availableForChat() {
        return DocumentStatus.AVAILABLE.name().equals(status);
    }
}
