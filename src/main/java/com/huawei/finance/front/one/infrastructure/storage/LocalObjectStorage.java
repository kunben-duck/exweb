package com.huawei.finance.front.one.infrastructure.storage;

import com.huawei.finance.front.one.application.gateway.ObjectStorage;
import com.huawei.finance.front.one.domain.document.StoredObject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 本地文件系统对象存储实现。
 *
 * <p>用于本地开发和第一版验证；生产环境通常替换为 OBS/S3/MinIO 等对象存储实现。</p>
 */
@Component
public class LocalObjectStorage implements ObjectStorage {
    private final Path root;
    public LocalObjectStorage(@Value("${financeex.storage.local-path:${java.io.tmpdir}/financeex-docs}") String root) { this.root = Path.of(root); }
    @Override public StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream) {
        try {
            // 按租户隔离目录，并用 UUID 前缀降低同名文件冲突概率。
            Files.createDirectories(root.resolve(tenantId));
            String objectKey = UUID.randomUUID() + "-" + originalFilename;
            Path target = root.resolve(tenantId).resolve(objectKey);
            Files.copy(inputStream, target);
            return new StoredObject("local", tenantId + "/" + objectKey, Files.size(target), contentType);
        } catch (Exception e) { throw new IllegalStateException("文档写入对象存储失败", e); }
    }
    @Override public String provider() { return "local"; }
}
