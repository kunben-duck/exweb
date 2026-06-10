package com.huawei.finance.front.one.interfaces.document.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * 前端文档库资产 DTO。
 *
 * <p>数据库和领域模型中的 {@code metadataJson} 仍以 JSON 字符串保存；接口层会解析成
 * {@link JsonNode} 返回，便于前端直接按对象读取 providerDocument、capabilities 等字段。</p>
 *
 * @param id 文档唯一标识，聊天附件只需要传该 ID。
 * @param tenantId 文档所属租户标识。
 * @param userId 文档所属用户标识。
 * @param sessionId 文档关联的会话标识，可为空。
 * @param originalName 用户可见文件名。
 * @param bucket provider 位置字段；对象存储表示 bucket，HTTP provider 表示 providerCode。
 * @param objectKey provider 文件标识；对象存储表示 object key，legacy-agent 表示老 Agent docId。
 * @param contentType 文件 MIME 类型。
 * @param sizeBytes 文件大小，单位字节。
 * @param status 文档状态，例如 AVAILABLE、PROCESSING、FAILED、DELETED。
 * @param source 文档来源，例如 LOCAL_UPLOAD、LEGACY_AGENT_UPLOAD。
 * @param metadataJson 文档扩展元数据 JSON 对象；为空表示没有扩展元数据。
 * @param tokenSize 文档解析后的 token 数量，可为空。
 * @param createdAt 创建时间。
 * @param updatedAt 最近更新时间。
 */
public record UploadedDocumentDto(
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
        JsonNode metadataJson,
        Long tokenSize,
        Instant createdAt,
        Instant updatedAt
) {}
