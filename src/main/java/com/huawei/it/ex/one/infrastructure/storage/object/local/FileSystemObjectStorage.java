package com.huawei.it.ex.one.infrastructure.storage.object.local;

import com.huawei.it.ex.one.application.integration.document.ObjectStorage;
import com.huawei.it.ex.one.domain.document.StoredObject;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 文件系统对象存储实现。
 *
 * <p>用于本地开发和第一版验证；生产环境通常替换为 huawei-s3 对象存储实现。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "local")
public class FileSystemObjectStorage implements ObjectStorage {
    private final Path root;

    public FileSystemObjectStorage(@Value("${financeex.storage.local-path:${java.io.tmpdir}/financeex-docs}") String root) {
        this.root = Path.of(root);
    }

    @Override
    public StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes,
                                  InputStream inputStream) {
        try {
            // 按租户隔离目录，并用 UUID 前缀降低同名文件冲突概率。
            String tenantPath = sanitizePathSegment(tenantId);
            Files.createDirectories(root.resolve(tenantPath));
            String objectKey = UUID.randomUUID() + "-" + sanitizeFilename(originalFilename);
            Path target = root.resolve(tenantPath).resolve(objectKey);
            Files.copy(inputStream, target);
            return new StoredObject("local", tenantPath + "/" + objectKey, Files.size(target), contentType);
        } catch (Exception e) {
            throw new IllegalStateException("文档写入对象存储失败", e);
        }
    }

    @Override
    public StoredObjectContent getObject(String bucket, String objectKey) {
        try {
            Path target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root.normalize())) {
                throw new SecurityException("非法对象路径");
            }
            return new StoredObjectContent(bucket, objectKey, Files.size(target), Files.probeContentType(target),
                    Files.newInputStream(target));
        } catch (Exception e) {
            throw new IllegalStateException("文档读取对象存储失败", e);
        }
    }

    @Override
    public String provider() {
        return "local";
    }

    private String sanitizeFilename(String originalFilename) {
        String filename = originalFilename == null || originalFilename.isBlank() ? "document" : originalFilename.trim();
        filename = filename.replace('\\', '/');
        int lastSlash = filename.lastIndexOf('/');
        if (lastSlash >= 0) {
            filename = filename.substring(lastSlash + 1);
        }
        filename = filename.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (filename.isBlank() || ".".equals(filename) || "..".equals(filename)) {
            return "document";
        }
        return filename.length() > 180 ? filename.substring(filename.length() - 180) : filename;
    }

    private String sanitizePathSegment(String value) {
        String sanitized = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_");
        if (sanitized.isBlank() || ".".equals(sanitized) || "..".equals(sanitized)) {
            throw new IllegalArgumentException("租户 ID 不能作为对象存储路径");
        }
        return sanitized;
    }
}
