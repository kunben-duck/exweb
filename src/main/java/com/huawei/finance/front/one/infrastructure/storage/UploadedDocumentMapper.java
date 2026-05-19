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
import org.apache.ibatis.annotations.Update;

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
            """)
    int insert(
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

    @Update("""
            UPDATE fin_ex_uploaded_document_t
            SET session_id = #{sessionId},
                original_name = #{originalName},
                bucket = #{bucket},
                object_key = #{objectKey},
                content_type = #{contentType},
                size_bytes = #{sizeBytes},
                status = #{status},
                source = #{source},
                token_size = #{tokenSize},
                metadata_json = #{metadataJson},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(
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
            <script>
            SELECT id, tenant_id, user_id, session_id, original_name, bucket, object_key,
                   content_type, size_bytes, status, source, token_size, metadata_json, created_at, updated_at
            FROM fin_ex_uploaded_document_t
            WHERE tenant_id = #{tenantId}
              AND user_id = #{userId}
              AND status &lt;&gt; 'DELETED'
              <if test="sessionId != null">
              AND session_id = #{sessionId}
              </if>
              <if test="cursorUpdatedAt != null">
              AND (
                    updated_at &lt; #{cursorUpdatedAt}
                    OR (updated_at = #{cursorUpdatedAt} AND id &lt; #{cursorId})
                  )
              </if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit}
            </script>
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
