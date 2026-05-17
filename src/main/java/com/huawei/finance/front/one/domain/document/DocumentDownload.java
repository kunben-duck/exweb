package com.huawei.finance.front.one.domain.document;

/**
 * 文档下载应用层结果。
 *
 * <p>下载需要同时返回数据库文档元数据和对象存储内容流。把二者封装为一个结果可以保证
 * Controller 只触发一次归属/状态校验，避免先查元数据、再下载对象时出现两次校验窗口。</p>
 *
 * @param document 已通过当前用户归属和可下载状态校验的文档元数据。
 * @param content 对象存储返回的内容流；调用方负责在异常路径关闭 inputStream。
 */
public record DocumentDownload(
        UploadedDocument document,
        StoredObjectContent content
) {}
