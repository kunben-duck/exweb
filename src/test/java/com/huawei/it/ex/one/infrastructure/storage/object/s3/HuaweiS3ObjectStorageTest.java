/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.storage.object.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.document.StoredObject;

import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.PutObjectResult;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

class HuaweiS3ObjectStorageTest {
    @Test
    void writesObjectToTenantScopedHuaweiS3Key() {
        RecordingHuaweiS3Operations operations = new RecordingHuaweiS3Operations();
        HuaweiS3ObjectStorage storage = new HuaweiS3ObjectStorage(operations, "financeex-documents", "uploads");

        StoredObject stored = storage.putObject("tenant/a", "../invoice image.pdf", "application/pdf", 3,
                new ByteArrayInputStream(new byte[] {1, 2, 3}));

        PutObjectRequest request = operations.lastPutRequest;
        assertThat(request.getBucketName()).isEqualTo("financeex-documents");
        assertThat(request.getObjectKey()).startsWith("uploads/tenant_a/");
        assertThat(request.getObjectKey()).endsWith("-invoice_image.pdf");
        assertThat(request.getObjectKey()).doesNotContain("..");
        assertThat(request.getMetadata().getContentType()).isEqualTo("application/pdf");
        assertThat(request.getMetadata().getContentLength()).isEqualTo(3L);
        assertThat(stored.bucket()).isEqualTo("financeex-documents");
        assertThat(stored.objectKey()).isEqualTo(request.getObjectKey());
        assertThat(stored.sizeBytes()).isEqualTo(3);
        assertThat(storage.provider()).isEqualTo("huawei-s3");
    }

    @Test
    void readsObjectFromHuaweiS3() {
        RecordingHuaweiS3Operations operations = new RecordingHuaweiS3Operations();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(5L);
        metadata.setContentType("text/plain");
        ObsObject obsObject = new ObsObject();
        obsObject.setMetadata(metadata);
        obsObject.setObjectContent(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        operations.objectToRead = obsObject;
        HuaweiS3ObjectStorage storage = new HuaweiS3ObjectStorage(operations, "financeex-documents", "documents");

        var content = storage.getObject("financeex-documents", "documents/tenant-a/hello.txt");

        assertThat(operations.lastReadBucket).isEqualTo("financeex-documents");
        assertThat(operations.lastReadObjectKey).isEqualTo("documents/tenant-a/hello.txt");
        assertThat(content.bucket()).isEqualTo("financeex-documents");
        assertThat(content.objectKey()).isEqualTo("documents/tenant-a/hello.txt");
        assertThat(content.sizeBytes()).isEqualTo(5L);
        assertThat(content.contentType()).isEqualTo("text/plain");
    }

    private static class RecordingHuaweiS3Operations implements HuaweiS3Operations {
        private PutObjectRequest lastPutRequest;
        private String lastReadBucket;
        private String lastReadObjectKey;
        private ObsObject objectToRead;

        @Override
        public PutObjectResult putObject(PutObjectRequest request) {
            this.lastPutRequest = request;
            return null;
        }

        @Override
        public ObsObject getObject(String bucket, String objectKey) {
            this.lastReadBucket = bucket;
            this.lastReadObjectKey = objectKey;
            return objectToRead;
        }
    }
}
