package com.huawei.finance.front.one.infrastructure.storage.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_uploaded_document_t 的 MyBatis Mapper。
 */
@Mapper
public interface UploadedDocumentMapper {
    @Insert("""
            INSERT INTO fin_ex_uploaded_document_t(
                id, tenant_id, user_id, session_id, original_name, bucket, object_key,
                content_type, size_bytes, status, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{originalName}, #{bucket}, #{objectKey},
                #{contentType}, #{sizeBytes}, #{status}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                session_id = EXCLUDED.session_id,
                original_name = EXCLUDED.original_name,
                bucket = EXCLUDED.bucket,
                object_key = EXCLUDED.object_key,
                content_type = EXCLUDED.content_type,
                size_bytes = EXCLUDED.size_bytes,
                status = EXCLUDED.status,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(
            @Param("id") String id,
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("originalName") String originalName,
            @Param("bucket") String bucket,
            @Param("objectKey") String objectKey,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("status") String status,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );
}
