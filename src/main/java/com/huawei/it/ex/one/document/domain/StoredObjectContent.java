package com.huawei.it.ex.one.document.domain;

import java.io.InputStream;

/**
 * 对象存储读取结果。
 *
 * @param bucket 对象所在 bucket。
 * @param objectKey 对象存储 key。
 * @param sizeBytes 对象大小，单位字节。
 * @param contentType 对象 MIME 类型。
 * @param inputStream 对象内容流；由响应框架或调用方负责关闭。
 */
public record StoredObjectContent(
        String bucket,
        String objectKey,
        long sizeBytes,
        String contentType,
        InputStream inputStream
) {}
