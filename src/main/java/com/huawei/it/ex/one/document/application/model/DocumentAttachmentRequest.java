package com.huawei.it.ex.one.document.application.model;

/**
 * 文档上下文接收的附件引用，只携带需要回查的稳定文档标识。
 *
 * @param documentId 文档标识。
 */
public record DocumentAttachmentRequest(String documentId) {
}
