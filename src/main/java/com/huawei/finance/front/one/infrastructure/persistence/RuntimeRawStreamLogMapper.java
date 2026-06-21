package com.huawei.finance.front.one.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;

/**
 * fin_ex_runtime_raw_stream_log_t 的 MyBatis Mapper。
 */
@Mapper
public interface RuntimeRawStreamLogMapper {
    /**
     * 写入一条下游 Runtime 原始流日志片段。
     *
     * @param row 原始流日志写入行，包含租户、用户、会话、run、provider、adapter、chunk 序号和裁剪状态。
     */
    void insert(RuntimeRawStreamLogWriteRow row);
}
