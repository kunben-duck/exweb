package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.Optional;

/**
 * 文档存储防腐层。
 *
 * <p>前端只调用统一文档上传接口，具体存储方式由 {@code financeex.storage.provider}
 * 在后端选择。当前支持 local、huawei-s3 和 api-store。</p>
 */
public interface DocumentStorage {
    /**
     * 上传文档并返回可持久化的文档库元数据。
     *
     * @param request 上传请求，包含入口用户身份、预生成 documentId 和文件流。
     * @return 文档库元数据。
     */
    UploadedDocument upload(DocumentStorageUploadRequest request);

    /**
     * 读取文档内容；由下游 API 托管的文档可以返回空。
     *
     * @param document 文档库元数据。
     * @return 文档二进制内容。
     */
    default Optional<StoredObjectContent> download(UploadedDocument document) {
        return Optional.empty();
    }

    /**
     * 当前存储实现是否支持通过本服务下载该文档。
     *
     * @param document 文档库元数据。
     * @return 支持下载时返回 true。
     */
    default boolean downloadSupported(UploadedDocument document) {
        return false;
    }
}
