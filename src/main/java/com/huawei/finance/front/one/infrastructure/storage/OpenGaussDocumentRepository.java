package com.huawei.finance.front.one.infrastructure.storage;

import com.huawei.finance.front.one.application.integration.document.DocumentRepository;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import com.huawei.finance.front.one.infrastructure.storage.mybatis.UploadedDocumentMapper;
import org.springframework.stereotype.Repository;

/**
 * 文档元数据 openGauss 仓储。
 *
 * <p>对象二进制内容仍由 ObjectStorage 保存；fin_ex_uploaded_document_t 只保存前端、会话和 Agent
 * 后续检索所需的稳定元数据。</p>
 */
@Repository
public class OpenGaussDocumentRepository implements DocumentRepository {
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
                document.createdAt(),
                document.updatedAt()
        );
        return document;
    }
}
