/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.storage.object.s3;

import com.obs.services.ObsClient;
import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.PutObjectResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 基于华为 OBS Java SDK 的对象操作实现。
 *
 * <p>该类是第三方 SDK 的薄适配层；对象路径、租户隔离和元数据策略仍由 {@link HuaweiS3ObjectStorage}
 * 负责。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.storage", name = "provider", havingValue = "huawei-s3")
public class HuaweiObsClientOperations implements HuaweiS3Operations {
    private final ObsClient obsClient;

    public HuaweiObsClientOperations(ObsClient obsClient) {
        this.obsClient = obsClient;
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        return obsClient.putObject(request);
    }

    @Override
    public ObsObject getObject(String bucket, String objectKey) {
        return obsClient.getObject(bucket, objectKey);
    }
}
