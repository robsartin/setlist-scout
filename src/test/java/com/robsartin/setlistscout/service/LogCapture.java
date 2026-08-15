package com.robsartin.setlistscout.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Test fixture: attaches a Logback {@link ListAppender} to a class's logger so tests can assert
 * on the SLF4J fluent-API key/value log lines (source, url, cause, ...) this codebase's
 * external-service classes emit. Use {@link #attachAt} when asserting on DEBUG output, since the
 * default root level is typically INFO and would otherwise filter those events before they reach
 * the appender.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger logger;
    private final Level originalLevel;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Class<?> loggedClass) {
        this.logger = (Logger) LoggerFactory.getLogger(loggedClass);
        this.originalLevel = logger.getLevel();
        this.appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Captures at the logger's current effective level. */
    public static LogCapture attach(Class<?> loggedClass) {
        return new LogCapture(loggedClass);
    }

    /** Captures at (at least) the given level, overriding the logger's configured level for the test. */
    public static LogCapture attachAt(Class<?> loggedClass, Level level) {
        LogCapture capture = new LogCapture(loggedClass);
        capture.logger.setLevel(level);
        return capture;
    }

    public List<ILoggingEvent> events() {
        return appender.list;
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
    }
}
