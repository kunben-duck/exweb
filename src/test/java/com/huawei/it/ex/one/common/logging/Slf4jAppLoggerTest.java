package com.huawei.it.ex.one.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class Slf4jAppLoggerTest {

    @Test
    void delegatesPlainAndParameterizedMessagesWithoutChangingArguments() {
        Logger delegate = mock(Logger.class);
        AppLogger logger = new Slf4jAppLogger(delegate);

        logger.info("plain message");
        logger.warn("value={}", "one");
        logger.debug("left={}, right={}", "one", "two");
        logger.error("first={}, second={}, third={}", "one", null, 3);

        verify(delegate).info("plain message");
        verify(delegate).warn("value={}", "one");
        verify(delegate).debug("left={}, right={}", "one", "two");
        verify(delegate).error("first={}, second={}, third={}", "one", null, 3);
    }

    @Test
    void delegatesNullSingleArgumentToTheMatchingSlf4jOverload() {
        Logger delegate = mock(Logger.class);
        AppLogger logger = new Slf4jAppLogger(delegate);

        logger.info("value={}", (Object) null);

        verify(delegate).info("value={}", (Object) null);
    }

    @Test
    void delegatesThrowableWithoutLosingTheThrowableOverload() {
        Logger delegate = mock(Logger.class);
        AppLogger logger = new Slf4jAppLogger(delegate);
        IllegalStateException failure = new IllegalStateException("failed");

        logger.warn("plain failure", failure);
        logger.error("runId={}, operation={}", "run_1", "relay", failure);

        verify(delegate).warn("plain failure", failure);
        verify(delegate).error("runId={}, operation={}", "run_1", "relay", failure);
    }

    @Test
    void delegatesLevelChecks() {
        Logger delegate = mock(Logger.class);
        AppLogger logger = new Slf4jAppLogger(delegate);
        when(delegate.isTraceEnabled()).thenReturn(true);
        when(delegate.isDebugEnabled()).thenReturn(true);
        when(delegate.isInfoEnabled()).thenReturn(true);
        when(delegate.isWarnEnabled()).thenReturn(true);
        when(delegate.isErrorEnabled()).thenReturn(true);

        assertThat(logger.isTraceEnabled()).isTrue();
        assertThat(logger.isDebugEnabled()).isTrue();
        assertThat(logger.isInfoEnabled()).isTrue();
        assertThat(logger.isWarnEnabled()).isTrue();
        assertThat(logger.isErrorEnabled()).isTrue();
    }

    @Test
    void factoryRejectsNullLoggerType() {
        assertThatNullPointerException()
                .isThrownBy(() -> AppLoggerFactory.getLogger(null))
                .withMessage("type");
    }
}
