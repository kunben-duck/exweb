package com.huawei.finance.front.one.infrastructure.storage;

import com.huawei.finance.front.one.application.integration.document.DocumentRepository;
import com.huawei.finance.front.one.domain.document.DocumentLibraryPage;
import com.huawei.finance.front.one.domain.document.DocumentLibraryQuery;
import com.huawei.finance.front.one.domain.document.DocumentSource;
import com.huawei.finance.front.one.domain.document.DocumentStatus;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.infrastructure.storage.mybatis.UploadedDocumentMapper;
import com.huawei.finance.front.one.infrastructure.storage.mybatis.UploadedDocumentRow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 文档元数据 openGauss 仓储。
 *
 * <p>对象二进制内容仍由 ObjectStorage 保存；fin_ex_uploaded_document_t 只保存前端、会话和 Agent
 * 后续检索所需的稳定元数据。</p>
 */
@Repository
public class OpenGaussDocumentRepository implements DocumentRepository {
    private static final String CURSOR_SEPARATOR = "|";

    private final UploadedDocumentMapper mapper;

    public OpenGaussDocumentRepository(UploadedDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public UploadedDocument save(UploadedDocument document) {
        mapper.upsert(
                document.id(),
                document.tenantId(),
                document.userId(),
                document.sessionId(),
                document.originalName(),
                document.bucket(),
                document.objectKey(),
                document.contentType(),
                document.sizeBytes(),
                document.status(),
                document.source(),
                document.tokenSize(),
                document.metadataJson(),
                document.createdAt(),
                document.updatedAt()
        );
        return document;
    }

    @Override
    public Optional<UploadedDocument> findByOwnerAndId(String tenantId, String userId, String documentId) {
        return mapper.findByOwnerAndId(tenantId, userId, documentId).map(this::toDomain);
    }

    @Override
    public DocumentLibraryPage listByOwner(String tenantId, String userId, DocumentLibraryQuery query) {
        Cursor cursor = decodeCursor(query.cursor());
        int pageSize = query.normalizedLimit();
        List<UploadedDocument> rows = mapper.listByOwner(
                        tenantId,
                        userId,
                        blankToNull(query.sessionId()),
                        cursor.updatedAt(),
                        cursor.id(),
                        pageSize + 1
                ).stream()
                .map(this::toDomain)
                .toList();
        boolean hasMore = rows.size() > pageSize;
        List<UploadedDocument> pageItems = hasMore ? rows.subList(0, pageSize) : rows;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new DocumentLibraryPage(pageItems, nextCursor);
    }

    private UploadedDocument toDomain(UploadedDocumentRow row) {
        return new UploadedDocument(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getOriginalName(),
                row.getBucket(),
                row.getObjectKey(),
                row.getContentType(),
                row.getSizeBytes() == null ? 0L : row.getSizeBytes(),
                row.getStatus() == null ? DocumentStatus.FAILED.name() : row.getStatus(),
                row.getSource() == null ? DocumentSource.LOCAL_UPLOAD.name() : row.getSource(),
                row.getTokenSize(),
                row.getMetadataJson(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                row.getUpdatedAt() == null ? Instant.EPOCH : row.getUpdatedAt()
        );
    }

    private String encodeCursor(UploadedDocument document) {
        String raw = document.updatedAt().toString() + CURSOR_SEPARATOR + document.id();
        return Base64.getUrlEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Cursor.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf(CURSOR_SEPARATOR);
            if (separator <= 0 || separator == raw.length() - 1) {
                return Cursor.empty();
            }
            return new Cursor(Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (RuntimeException ex) {
            return Cursor.empty();
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Cursor(Instant updatedAt, String id) {
        static Cursor empty() {
            return new Cursor(null, null);
        }
    }
}
