package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.application.config.DocumentProviderProperties;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.Optional;

/**
 * 文档 provider 防腐层。
 *
 * <p>不同领域 Agent 可能要求先调用自己的文件上传接口，再把 provider 文件 ID 传给 chat 接口。
 * ChatService 通过该端口统一上传、下载和状态能力，文档库对前端始终暴露统一 documentId。</p>
 */
public interface DocumentProviderAdapter {
    /**
     * 当前 adapter 是否支持指定 provider 类型。
     *
     * @param providerType provider 配置中的 type。
     * @return 支持时返回 true。
     */
    boolean supportsType(String providerType);

    /**
     * 上传文档到 provider，并返回统一文档库元数据。
     *
     * @param request provider 上传请求。
     * @return 统一文档库元数据，调用方负责持久化。
     */
    UploadedDocument upload(DocumentProviderUploadRequest request);

    /**
     * 代理读取 provider 中的文件内容。
     *
     * @param document 文档库元数据。
     * @param provider provider 配置。
     * @return 支持下载时返回内容流；不支持时返回 empty。
     */
    default Optional<StoredObjectContent> download(UploadedDocument document,
                                                   DocumentProviderProperties.ProviderEntry provider) {
        return Optional.empty();
    }

    /**
     * provider 是否支持通过本服务下载该文档。
     *
     * @param document 文档库元数据。
     * @param provider provider 配置。
     * @return 支持下载时返回 true。
     */
    default boolean downloadSupported(UploadedDocument document, DocumentProviderProperties.ProviderEntry provider) {
        return false;
    }
}
