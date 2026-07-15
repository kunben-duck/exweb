package com.huawei.it.ex.one.common.logging;

import java.util.Objects;
import org.slf4j.LoggerFactory;

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
