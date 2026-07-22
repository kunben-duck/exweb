package com.huawei.it.ex.one.infrastructure.runtime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

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
     * 按主键查询 RuntimeBinding。
     *
     * @param bindingId 绑定主键。
     * @return binding 行；不存在时为 {@code null}。
     */
    RuntimeBindingRow findById(@Param("bindingId") String bindingId);

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

    /**
     * 查询会话下所有 provider 的 active RuntimeBinding。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @return active binding 列表，按更新时间倒序。
     */
    List<RuntimeBindingRow> findActiveBySessionAnyProvider(@Param("tenantId") String tenantId,
                                                           @Param("userId") String userId,
                                                           @Param("sessionId") String sessionId);

    /**
     * 查询会话下指定 provider 的可恢复 RuntimeBinding，按更新时间倒序。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId ChatService 会话标识。
     * @param provider Runtime provider。
     * @return 可恢复 binding 列表。
     */
    List<RuntimeBindingRow> findResumableBySession(@Param("tenantId") String tenantId,
                                                   @Param("userId") String userId,
                                                   @Param("sessionId") String sessionId,
                                                   @Param("provider") String provider);
}
