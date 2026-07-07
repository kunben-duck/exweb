package com.huawei.finance.front.one.infrastructure.storage.object.s3;

import com.obs.services.model.ObsObject;
import com.obs.services.model.PutObjectRequest;
import com.obs.services.model.PutObjectResult;

/**
 * 华为 OBS SDK 的最小操作面。
 *
 * <p>该接口只放在 infrastructure 层，用于隔离第三方 SDK 复杂类型层级，避免业务存储实现和测试直接依赖
 * {@code ObsClient} 的大型继承结构。</p>
 */
public interface HuaweiS3Operations {
    /**
     * 写入对象。
     *
     * @param request 华为 OBS putObject 请求。
     * @return 写入结果。
     */
    PutObjectResult putObject(PutObjectRequest request);

    /**
     * 读取对象。
     *
     * @param bucket bucket 名称。
     * @param objectKey 对象 key。
     * @return OBS 对象内容。
     */
    ObsObject getObject(String bucket, String objectKey);
}
