package org.example.sporthealth.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.ai.mcp.annotation.McpTool;

import tools.jackson.databind.ObjectMapper;

/**
 * 工具访问日志切面测试：每次调用前后各一条日志，工具名 / 入参 / status / 耗时是否齐全，异常是否按 ERROR 记录。
 */
class ToolAccessLogAspectTest {

    private static final Pattern CALL_ID = Pattern.compile("callId=(\\S+)");

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger accessLogger;

    @BeforeEach
    void attachAppender() {
        accessLogger = (Logger) LoggerFactory.getLogger(ToolAccessLogAspect.class);
        accessLogger.setLevel(Level.INFO);
        appender.start();
        accessLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        accessLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsStartAndEndAroundEachCall() {
        SampleTools tools = proxy(new SampleTools());

        Map<String, Object> result = tools.search("20261101", 5);

        assertEquals("OK", result.get("status"));
        String start = eventAt(0);
        String end = eventAt(1);

        assertTrue(start.startsWith("phase=START"), start);
        assertTrue(start.contains("tool=sample_search"), start);
        assertTrue(start.contains("\"runId\":\"20261101\""), start);
        assertTrue(start.contains("\"limit\":5"), start);

        assertTrue(end.startsWith("phase=END"), end);
        assertTrue(end.contains("tool=sample_search"), end);
        assertTrue(end.contains("status=OK"), end);
        assertTrue(end.contains("durationMs="), end);

        assertEquals(callIdOf(start), callIdOf(end), "执行前后两条日志用 callId 关联");
    }

    @Test
    void keepsBusinessStatusFromResult() {
        SampleTools tools = proxy(new SampleTools());

        tools.missing();

        String start = eventAt(0);
        String end = eventAt(1);
        assertTrue(start.contains("tool=sample_missing"), start);
        assertTrue(end.contains("status=NOT_FOUND"), end);
        assertEquals(callIdOf(start), callIdOf(end));
    }

    @Test
    void logsFailureAtErrorLevelAndRethrows() {
        SampleTools tools = proxy(new SampleTools());

        IllegalStateException error = assertThrows(IllegalStateException.class, tools::boom);

        assertEquals("boom", error.getMessage());
        String start = eventAt(0);
        ILoggingEvent endEvent = appender.list.get(1);
        assertEquals(Level.ERROR, endEvent.getLevel());
        String end = endEvent.getFormattedMessage();
        assertTrue(end.contains("status=ERROR"), end);
        assertTrue(end.contains("error=IllegalStateException: boom"), end);
        assertNotNull(endEvent.getThrowableProxy(), "异常堆栈要一起记录");
        assertEquals(callIdOf(start), callIdOf(end));
    }

    /** 每次调用固定两条：索引 0 是 START，索引 1 是 END。 */
    private String eventAt(int index) {
        assertEquals(2, appender.list.size(), "执行前后各一条日志");
        return appender.list.get(index).getFormattedMessage();
    }

    private static String callIdOf(String message) {
        Matcher matcher = CALL_ID.matcher(message);
        assertTrue(matcher.find(), "日志要带 callId: " + message);
        return matcher.group(1);
    }

    private static <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new ToolAccessLogAspect(new ObjectMapper()));
        return factory.getProxy();
    }

    /** 只用于验证切面，不进入 Spring 上下文。 */
    static class SampleTools {

        @McpTool(name = "sample_search", description = "demo")
        public Map<String, Object> search(String runId, Integer limit) {
            return Map.of("status", "OK", "runId", runId, "limit", limit);
        }

        @McpTool(name = "sample_missing", description = "demo")
        public Map<String, Object> missing() {
            return Map.of("status", "NOT_FOUND");
        }

        @McpTool(name = "sample_boom", description = "demo")
        public Map<String, Object> boom() {
            throw new IllegalStateException("boom");
        }
    }
}
