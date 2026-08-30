/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.common.trace;

/**
 * 请求入口捕获的链路追踪上下文。
 *
 * <p>该对象只承载可信服务端 Provider 解析出的字段，并作为不可变快照跨异步边界传递。
 * 本版本不负责创建本地 traceId，也不参与持久化。</p>
 *
 * @param traceId 当前请求的链路追踪标识；未取得时为空。
 */
public record TraceContext(String traceId) {
    private static final TraceContext EMPTY = new TraceContext(null);

    public TraceContext {
        traceId = normalize(traceId);
    }

    public static TraceContext empty() {
        return EMPTY;
    }

    public boolean hasTraceId() {
        return traceId != null;
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
