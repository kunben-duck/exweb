/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.document;

/**
 * 对象存储写入结果。
 *
 * @param bucket 对象所在 bucket。
 * @param objectKey 对象存储 key。
 * @param sizeBytes 对象大小，单位字节。
 * @param contentType 对象 MIME 类型。
 */
public record StoredObject(
        String bucket,
        String objectKey,
        long sizeBytes,
        String contentType
) {}
