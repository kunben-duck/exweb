package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.Optional;

/**
 * 文档库元数据仓储端口。
 *
 * <p>该端口只保存文档资产的结构化元数据；文件二进制由 ObjectStorage 端口负责。</p>
 */
public interface DocumentRepository {
    /**
     * 新增或更新文档元数据。
     *
     * @param document 文档资产。
     * @return 已保存的文档资产。
     */
    UploadedDocument save(UploadedDocument document);

    /**
     * 按归属查询文档。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param documentId 文档标识。
     * @return 文档元数据；不存在或不属于当前用户时为空。
     */
    Optional<UploadedDocument> findByOwnerAndId(String tenantId, String userId, String documentId);

    /**
     * 查询用户文档库。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param query 查询条件。
     * @return 文档分页结果。
     */
    DocumentLibraryPage listByOwner(String tenantId, String userId, DocumentLibraryQuery query);
}
