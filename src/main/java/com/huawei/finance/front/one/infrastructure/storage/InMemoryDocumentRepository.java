package com.huawei.finance.front.one.infrastructure.storage;

import com.huawei.finance.front.one.application.gateway.DocumentRepository;
import com.huawei.finance.front.one.domain.document.UploadedDocument;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryDocumentRepository implements DocumentRepository {
    private final Map<String, UploadedDocument> store = new ConcurrentHashMap<>();
    @Override public UploadedDocument save(UploadedDocument document) { store.put(document.id(), document); return document; }
}
