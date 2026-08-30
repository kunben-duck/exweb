/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.trace;

import com.huawei.it.ex.one.application.integration.trace.TraceContextProvider;
import com.huawei.it.ex.one.common.trace.TraceContext;

/**
 * Jalor 链路追踪上下文 Provider。
 *
 * <p>当前保留安全空占位；接入企业 Jalor SDK 后，只需替换
 * {@link #resolveTraceIdFromJalor()} 内的取值表达式。</p>
 */
public final class JalorTraceContextProvider implements TraceContextProvider {
    @Override
    public TraceContext resolve() {
        return new TraceContext(resolveTraceIdFromJalor());
    }

    private String resolveTraceIdFromJalor() {
        return "";
    }
}
