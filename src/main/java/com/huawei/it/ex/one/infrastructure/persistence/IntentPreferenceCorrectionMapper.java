package com.huawei.it.ex.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** MyBatis mapper for Intent preference corrections. */
@Mapper
public interface IntentPreferenceCorrectionMapper {
    /**
     * 按 owner、Intent入口和source消息原子新增或覆盖偏好记录。
     *
     * @param row 偏好写入行，包含归属、来源、选择结果和服务端时间。
     */
    void upsert(IntentPreferenceCorrectionWriteRow row);

    /**
     * 查询指定用户和Intent入口最近更新的偏好。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param intentAccessName 有效Intent入口名称。
     * @param limit 最大返回条数。
     * @return 按更新时间从新到旧排列的偏好摘要。
     */
    List<IntentPreferenceCorrectionReadRow> findRecent(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("intentAccessName") String intentAccessName,
            @Param("limit") int limit);
}
