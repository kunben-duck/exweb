package com.huawei.finance.front.one.domain.document;

public record StoredObject(String bucket, String objectKey, long sizeBytes, String contentType) {}
