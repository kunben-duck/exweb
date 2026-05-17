package com.huawei.finance.front.one.application.integration.document;

import com.huawei.finance.front.one.domain.document.StoredObject;
import com.huawei.finance.front.one.domain.document.StoredObjectContent;
import java.io.InputStream;

/**
 * 对象存储防腐层。
 *
 * <p>统一后端接收文件后，通过该端口写入真实对象存储。当前可由本地文件系统或华为 OBS S3
 * adapter 实现，上层文档库不感知具体厂商。</p>
 */
public interface ObjectStorage {
    /**
     * 写入对象。
     *
     * @param tenantId 租户标识，用于对象路径隔离。
     * @param originalFilename 原始文件名。
     * @param contentType 文件 MIME 类型。
     * @param sizeBytes 文件大小。
     * @param inputStream 文件内容输入流，由调用方负责关闭。
     * @return 对象存储写入结果。
     */
    StoredObject putObject(String tenantId, String originalFilename, String contentType, long sizeBytes, InputStream inputStream);

    /**
     * 读取对象内容。
     *
     * @param bucket 对象所在 bucket。
     * @param objectKey 对象存储 key。
     * @return 对象内容流。
     */
    StoredObjectContent getObject(String bucket, String objectKey);

    /**
     * @return 存储实现标识，例如 local、huawei-s3。
     */
    String provider();
}
