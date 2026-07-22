package com.huawei.it.ex.one.interfaces.document.dto;

import com.huawei.it.ex.one.domain.document.DocumentLibraryPage;
import com.huawei.it.ex.one.domain.document.UploadedDocument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档接口 DTO 转换器。
 *
 * <p>领域模型需要把 metadataJson 作为字符串交给数据库持久化；接口响应则应该返回结构化
 * JSON，避免前端在每个文档入口都手工 {@code JSON.parse}。该转换器只做展示层解析，不改变
 * 数据库存储格式。</p>
 */
@Component
public class DocumentDtoMapper {
    private final ObjectMapper objectMapper;

    public DocumentDtoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 转换单个文档元数据。
     *
     * @param document 领域文档对象。
     * @return 前端文档 DTO。
     */
    public UploadedDocumentDto toDto(UploadedDocument document) {
        if (document == null) {
            return null;
        }
        return new UploadedDocumentDto(
                document.id(),
                document.tenantId(),
                document.userId(),
                document.sessionId(),
                document.originalName(),
                document.bucket(),
                document.objectKey(),
                document.contentType(),
                document.sizeBytes(),
                document.status(),
                document.source(),
                metadataNode(document.metadataJson()),
                document.tokenSize(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    /**
     * 转换文档库分页结果。
     *
     * @param page 领域分页结果。
     * @return 前端分页 DTO。
     */
    public DocumentLibraryPageDto toDto(DocumentLibraryPage page) {
        if (page == null) {
            return new DocumentLibraryPageDto(List.of(), null);
        }
        return new DocumentLibraryPageDto(
                page.items().stream().map(this::toDto).toList(),
                page.nextCursor()
        );
    }

    private JsonNode metadataNode(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(metadataJson);
        } catch (Exception ignored) {
            /*
             * 历史数据或人工修复数据可能不是合法 JSON。接口层不能因为展示字段解析失败而阻断文档库，
             * 因此保底按 JSON string 返回，方便前端仍可展示原始内容。
             */
            return TextNode.valueOf(metadataJson);
        }
    }
}
