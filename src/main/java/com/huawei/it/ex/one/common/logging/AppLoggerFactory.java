/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.common.logging;

import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Single application entry point for creating loggers.
 */
public final class AppLoggerFactory {

    private AppLoggerFactory() {
    }

    public static AppLogger getLogger(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return new Slf4jAppLogger(LoggerFactory.getLogger(type));
    }
}
