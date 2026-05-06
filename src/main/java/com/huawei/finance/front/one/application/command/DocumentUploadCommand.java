package com.huawei.finance.front.one.application.command;

import java.io.InputStream;

public record DocumentUploadCommand(
        String sessionId,
        String originalFilename,
        String contentType,
        long sizeBytes,
        InputStream inputStream
) {}
