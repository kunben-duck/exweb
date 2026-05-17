package com.huawei.finance.front.one.interfaces.document;

/**
 * 前端更新文档元数据请求。
 *
 * @param originalName 新展示文件名；为空时保留原名称。
 * @param metadataJson 新扩展元数据 JSON；为空时保留原元数据。
 */
public record UpdateDocumentRequest(
        String originalName,
        String metadataJson
) {}
