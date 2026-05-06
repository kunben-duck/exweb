package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.command.DocumentUploadCommand;
import com.huawei.finance.front.one.application.facade.DocumentUploadFacade;
import com.huawei.finance.front.one.application.integration.document.DocumentRepository;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.document.ObjectStorage;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.domain.auth.UserContext;
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
    private final SessionRepository sessionRepository;
    private final IdGenerator idGenerator;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    public DocumentApplicationService(ObjectStorage storage, DocumentRepository repository, SessionRepository sessionRepository,
                                      IdGenerator idGenerator, AuthContextProvider auth, PermissionChecker permissionChecker) {
        this.storage = storage; this.repository = repository; this.sessionRepository = sessionRepository; this.idGenerator = idGenerator;
        this.auth = auth; this.permissionChecker = permissionChecker;
    }
    @Override
    public Mono<UploadedDocument> upload(DocumentUploadCommand command) {
        return Mono.fromCallable(() -> {
            // 文档接口也不信任前端身份，统一由应用身份防腐层解析当前用户。
            UserContext user = auth.resolve();
            permissionChecker.checkChatPermission(user);
            ensureOwnedSessionIfPresent(user, command.sessionId());

            // 先存对象，再保存数据库可检索的文档记录；对象 key 和文档行都使用同一份 UserContext。
            StoredObject object = storage.putObject(user.tenantId(), command.originalFilename(), command.contentType(), command.sizeBytes(), command.inputStream());
            String documentId = idGenerator.newId("doc", IdGenerateContext.of(user.tenantId(), user.userId(), command.sessionId()));
            UploadedDocument doc = new UploadedDocument(documentId, user.tenantId(), user.userId(), command.sessionId(), command.originalFilename(), object.bucket(), object.objectKey(), command.contentType(), object.sizeBytes(), "UPLOADED", Instant.now(), Instant.now());
            return repository.save(doc);
        });
    }

    private void ensureOwnedSessionIfPresent(UserContext user, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (sessionRepository.findByTenantIdAndUserIdAndId(user.tenantId(), user.userId(), sessionId).isEmpty()) {
            throw new SecurityException("文档不能绑定到不属于当前用户的会话");
        }
    }
}
