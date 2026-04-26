package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentUploadFacade;
import com.huawei.finance.front.one.application.gateway.DocumentRepository;
import com.huawei.finance.front.one.application.gateway.IdGenerateContext;
import com.huawei.finance.front.one.application.gateway.IdGenerator;
import com.huawei.finance.front.one.application.gateway.ObjectStorage;
import com.huawei.finance.front.one.domain.document.StoredObject;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.time.Instant;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 文档上传应用服务。
 *
 * <p>对象内容写入 ObjectStorage，文档元数据写入 DocumentRepository。</p>
 */
@Service
public class DocumentApplicationService implements DocumentUploadFacade {
    private final ObjectStorage storage;
    private final DocumentRepository repository;
    private final IdGenerator idGenerator;
    public DocumentApplicationService(ObjectStorage storage, DocumentRepository repository, IdGenerator idGenerator) {
        this.storage = storage; this.repository = repository; this.idGenerator = idGenerator;
    }
    @Override
    public Mono<UploadedDocument> upload(DocumentUploadCommand command) {
        return Mono.fromCallable(() -> {
            // 先存对象，再保存数据库可检索的文档记录。
            StoredObject object = storage.putObject(command.tenantId(), command.originalFilename(), command.contentType(), command.sizeBytes(), command.inputStream());
            String documentId = idGenerator.newId("doc", IdGenerateContext.of(command.tenantId(), command.userId(), command.sessionId()));
            UploadedDocument doc = new UploadedDocument(documentId, command.tenantId(), command.userId(), command.sessionId(), command.originalFilename(), object.bucket(), object.objectKey(), command.contentType(), object.sizeBytes(), "UPLOADED", Instant.now(), Instant.now());
            return repository.save(doc);
        });
    }
}
