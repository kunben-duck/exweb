package com.huawei.finance.front.one.infrastructure.runtime;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * fin_ex_runtime_binding_t 的 MyBatis mapper。
 */
@Mapper
public interface RuntimeBindingMapper {
    /**
     * 创建消息树 leaf 与下游 Runtime session 的绑定。
     *
     * @param row binding 写入行，包含归属、provider、leaf、runtimeSessionId、状态和过期时间。
     * @return 影响行数。
     */
    int insert(RuntimeBindingRow row);

    /**
     * 更新 RuntimeBinding 状态和续接信息。
     *
     * @param row binding 更新行，id 定位记录。
     * @return 影响行数。
     */
    int update(RuntimeBindingRow row);

    /**
     * 查询指定 leaf 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @param leafMessageId 消息树 leaf；为空表示绑定在空 leaf 上。
     * @return active binding；不存在时为 {@code null}。
     */
    RuntimeBindingRow findActive(@Param("tenantId") String tenantId,
                                 @Param("userId") String userId,
                                 @Param("sessionId") String sessionId,
                                 @Param("provider") String provider,
                                 @Param("leafMessageId") String leafMessageId);

    /**
     * 查询会话下指定 provider 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @return active binding 列表。
     */
    List<RuntimeBindingRow> findActiveBySession(@Param("tenantId") String tenantId,
                                                @Param("userId") String userId,
                                                @Param("sessionId") String sessionId,
                                                @Param("provider") String provider);
}
