package com.huawei.finance.front.one.domain.document;

/**
 * 文档来源。
 *
 * <p>来源用于前端展示和审计。无论来自本地上传、文档库复用还是未来连接器导入，聊天请求最终都只引用
 * 统一的 documentId。</p>
 */
public enum DocumentSource {
    /** 用户通过本服务上传的本地文件。 */
    LOCAL_UPLOAD,
    /** 用户从本服务文档库中选择的历史文档。 */
    LIBRARY,
    /** 用户从外部连接器导入的云端文档。 */
    CONNECTOR
}
