package com.huawei.finance.front.one.application.command;

import java.io.InputStream;

public record DocumentUploadCommand(
        String tenantId,
        String userId,
        String sessionId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        InputStream inputStream
) {}
