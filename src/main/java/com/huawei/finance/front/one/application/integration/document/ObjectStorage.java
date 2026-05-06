package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.domain.document.StoredObject;
import java.io.InputStream;

public interface ObjectStorage {
    StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream);
    String provider();
}
