package com.huawei.finance.front.one.infrastructure.storage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/**
 * fin_ex_uploaded_document_t 的 MyBatis Mapper。
 */
@Mapper
public interface UploadedDocumentMapper {
    @Insert("""
            INSERT INTO fin_ex_uploaded_document_t(
                id, tenant_id, user_id, session_id, original_name, bucket, object_key,
                content_type, size_bytes, status, source, token_size, metadata_json, created_at, updated_at
            )
            VALUES (
                #{id}, #{tenantId}, #{userId}, #{sessionId}, #{originalName}, #{bucket}, #{objectKey},
                #{contentType}, #{sizeBytes}, #{status}, #{source}, #{tokenSize}, #{metadataJson}, #{createdAt}, #{updatedAt}
            )
            ON CONFLICT (id) DO UPDATE SET
                session_id = EXCLUDED.session_id,
                original_name = EXCLUDED.original_name,
                bucket = EXCLUDED.bucket,
                object_key = EXCLUDED.object_key,
                content_type = EXCLUDED.content_type,
                size_bytes = EXCLUDED.size_bytes,
                status = EXCLUDED.status,
                source = EXCLUDED.source,
                token_size = EXCLUDED.token_size,
                metadata_json = EXCLUDED.metadata_json,
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
            @Param("source") String source,
            @Param("tokenSize") Long tokenSize,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, original_name, bucket, object_key,
                   content_type, size_bytes, status, source, token_size, metadata_json, created_at, updated_at
            FROM fin_ex_uploaded_document_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND id = #{id}
              AND status <> 'DELETED'
            """)
    @Results(id = "uploadedDocumentResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "original_name", property = "originalName"),
            @Result(column = "object_key", property = "objectKey"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "size_bytes", property = "sizeBytes"),
            @Result(column = "token_size", property = "tokenSize"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    Optional<UploadedDocumentRow> findByOwnerAndId(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("id") String id
    );

    @Select("""
            SELECT id, tenant_id, user_id, session_id, original_name, bucket, object_key,
                   content_type, size_bytes, status, source, token_size, metadata_json, created_at, updated_at
            FROM fin_ex_uploaded_document_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status <> 'DELETED'
              AND (#{sessionId} IS NULL OR session_id = #{sessionId})
              AND (
                    #{cursorUpdatedAt} IS NULL
                    OR updated_at < #{cursorUpdatedAt}
                    OR (updated_at = #{cursorUpdatedAt} AND id < #{cursorId})
                  )
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            """)
    @Results(id = "uploadedDocumentListResultMap", value = {
            @Result(column = "tenant_id", property = "tenantId"),
            @Result(column = "user_id", property = "userId"),
            @Result(column = "session_id", property = "sessionId"),
            @Result(column = "original_name", property = "originalName"),
            @Result(column = "object_key", property = "objectKey"),
            @Result(column = "content_type", property = "contentType"),
            @Result(column = "size_bytes", property = "sizeBytes"),
            @Result(column = "token_size", property = "tokenSize"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<UploadedDocumentRow> listByOwner(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("cursorUpdatedAt") Instant cursorUpdatedAt,
            @Param("cursorId") String cursorId,
            @Param("limit") int limit
    );
}
