package com.huawei.finance.front.one.infrastructure.storage.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_uploaded_document_t 的 MyBatis Mapper。
 */
@Mapper
public interface UploadedDocumentMapper {
    /**
     * 写入统一文档库元数据。
     *
     * @param row 文档写入行，包含归属、provider 定位符、状态、来源和 metadataJson。
     * @return 影响行数。
     */
    int insert(UploadedDocumentRow row);

    /**
     * 更新文档库元数据或状态。
     *
     * @param row 文档更新行，id 定位记录。
     * @return 影响行数。
     */
    int update(UploadedDocumentRow row);

    /**
     * 按 owner 边界查询未删除文档。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param id 文档标识。
     * @return 文档行。
     */
    Optional<UploadedDocumentRow> findByOwnerAndId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("id") String id
    );

    /**
     * 游标分页查询当前用户未删除文档。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 可选会话过滤条件。
     * @param cursorUpdatedAt 上一页最后一条文档的更新时间，可为空。
     * @param cursorId 上一页最后一条文档 ID，可为空。
     * @param limit 最大返回条数。
     * @return 文档列表。
     */
    List<UploadedDocumentRow> listByOwner(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );
}
