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
    CONNECTOR,
    /** 文档由 api-store 通过 skillId 上传到下游 EDM，本服务仅保存统一文档库元数据。 */
    DOMAIN_AGENT_UPLOAD,
    /** 文档由 api-store 上传到下游 S3，本服务保存统一文档库元数据。 */
    S3_UPLOAD
}
