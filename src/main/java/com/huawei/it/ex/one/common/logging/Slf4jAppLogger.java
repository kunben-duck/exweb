package com.huawei.it.ex.one.common.logging;

import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * SLF4J-backed implementation of the application logging facade.
 */
final class Slf4jAppLogger implements AppLogger {

    private final Logger delegate;

    Slf4jAppLogger(Logger delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public void trace(String message) {
        delegate.trace(message);
    }

    @Override
    public void trace(String format, Object argument) {
        delegate.trace(format, argument);
    }

    @Override
    public void trace(String format, Object argument1, Object argument2) {
        delegate.trace(format, argument1, argument2);
    }

    @Override
    public void trace(String format, Object... arguments) {
        delegate.trace(format, arguments);
    }

    @Override
    public void trace(String message, Throwable throwable) {
        delegate.trace(message, throwable);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public void debug(String message) {
        delegate.debug(message);
    }

    @Override
    public void debug(String format, Object argument) {
        delegate.debug(format, argument);
    }

    @Override
    public void debug(String format, Object argument1, Object argument2) {
        delegate.debug(format, argument1, argument2);
    }

    @Override
    public void debug(String format, Object... arguments) {
        delegate.debug(format, arguments);
    }

    @Override
    public void debug(String message, Throwable throwable) {
        delegate.debug(message, throwable);
    }

    @Override
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    @Override
    public void info(String message) {
        delegate.info(message);
    }

    @Override
    public void info(String format, Object argument) {
        delegate.info(format, argument);
    }

    @Override
    public void info(String format, Object argument1, Object argument2) {
        delegate.info(format, argument1, argument2);
    }

    @Override
    public void info(String format, Object... arguments) {
        delegate.info(format, arguments);
    }

    @Override
    public void info(String message, Throwable throwable) {
        delegate.info(message, throwable);
    }

    @Override
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    @Override
    public void warn(String message) {
        delegate.warn(message);
    }

    @Override
    public void warn(String format, Object argument) {
        delegate.warn(format, argument);
    }

    @Override
    public void warn(String format, Object argument1, Object argument2) {
        delegate.warn(format, argument1, argument2);
    }

    @Override
    public void warn(String format, Object... arguments) {
        delegate.warn(format, arguments);
    }

    @Override
    public void warn(String message, Throwable throwable) {
        delegate.warn(message, throwable);
    }

    @Override
    public void warn(SystemErrorLogEntry event) {
        delegate.warn(SystemErrorLogFormatter.format(Objects.requireNonNull(event, "event"), null));
    }

    @Override
    public void warn(SystemErrorLogEntry event, Throwable throwable) {
        delegate.warn(SystemErrorLogFormatter.format(Objects.requireNonNull(event, "event"), throwable), throwable);
    }

    @Override
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public void error(String message) {
        delegate.error(message);
    }

    @Override
    public void error(String format, Object argument) {
        delegate.error(format, argument);
    }

    @Override
    public void error(String format, Object argument1, Object argument2) {
        delegate.error(format, argument1, argument2);
    }

    @Override
    public void error(String format, Object... arguments) {
        delegate.error(format, arguments);
    }

    @Override
    public void error(String message, Throwable throwable) {
        delegate.error(message, throwable);
    }

    @Override
    public void error(SystemErrorLogEntry event) {
        delegate.error(SystemErrorLogFormatter.format(Objects.requireNonNull(event, "event"), null));
    }

    @Override
    public void error(SystemErrorLogEntry event, Throwable throwable) {
        delegate.error(SystemErrorLogFormatter.format(Objects.requireNonNull(event, "event"), throwable), throwable);
    }
}
