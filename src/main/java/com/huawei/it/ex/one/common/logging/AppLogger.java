package com.huawei.it.ex.one.common.logging;

/**
 * Application logging facade that keeps business code independent from the logging backend.
 */
public interface AppLogger {

    boolean isTraceEnabled();

    void trace(String message);

    void trace(String format, Object argument);

    void trace(String format, Object argument1, Object argument2);

    void trace(String format, Object... arguments);

    void trace(String message, Throwable throwable);

    boolean isDebugEnabled();

    void debug(String message);

    void debug(String format, Object argument);

    void debug(String format, Object argument1, Object argument2);

    void debug(String format, Object... arguments);

    void debug(String message, Throwable throwable);

    boolean isInfoEnabled();

    void info(String message);

    void info(String format, Object argument);

    void info(String format, Object argument1, Object argument2);

    void info(String format, Object... arguments);

    void info(String message, Throwable throwable);

    boolean isWarnEnabled();

    void warn(String message);

    void warn(String format, Object argument);

    void warn(String format, Object argument1, Object argument2);

    void warn(String format, Object... arguments);

    void warn(String message, Throwable throwable);

    boolean isErrorEnabled();

    void error(String message);

    void error(String format, Object argument);

    void error(String format, Object argument1, Object argument2);

    void error(String format, Object... arguments);

    void error(String message, Throwable throwable);
}
