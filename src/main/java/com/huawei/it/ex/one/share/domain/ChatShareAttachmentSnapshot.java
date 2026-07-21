package com.huawei.it.ex.one.share.domain;

/**
 * 分享快照中的附件展示信息。
 *
 * <p>该快照只用于展示，不授予文档下载或预览权限。</p>
 */
public record ChatShareAttachmentSnapshot(
        String documentId,
        String name,
        String contentType,
        Long sizeBytes
) {}
