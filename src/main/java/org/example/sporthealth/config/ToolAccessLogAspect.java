package org.example.sporthealth.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * MCP 工具调用的全局访问日志。
 *
 * <p>切所有标注 {@link McpTool} 的工具方法，每次调用记两条：执行前一条 {@code phase=START}
 * （工具名 + 入参），执行后一条 {@code phase=END}（工具名 + 返回体状态 + 耗时）；
 * 两条用同一个 {@code callId} 关联，并发调用也能配对。异常时 {@code phase=END} 按 ERROR
 * 记录并原样抛出，客户端收到的错误不变。
 *
 * <p>按注解切面，不限定包：任何 bean 上标注 {@link McpTool} 的方法都会记录，
 * 新增工具不需要在方法里再写日志。
 *
 * <p>日志走类名 logger（Lombok {@code @Slf4j}），级别由 logback.xml 里同名 logger 的
 * {@code LOG_LEVEL_TOOL_ACCESS} 控制，不需要访问日志时设为 OFF。
 */
@Aspect
@Component
@Slf4j
public class ToolAccessLogAspect {

    /** 入参 JSON 的日志上限，避免超大列表把日志冲垮。 */
    private static final int MAX_ARGUMENT_LENGTH = 512;

    /** 入参里的换行会破坏“一行一次调用”，统一压成空格。 */
    private static final Pattern BLANKS = Pattern.compile("\\s+");

    /** 每次调用的序号，START / END 两条日志靠它关联。 */
    private static final AtomicLong CALL_SEQUENCE = new AtomicLong();

    private final ObjectMapper objectMapper;

    public ToolAccessLogAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint, McpTool tool) throws Throwable {
        String callId = "call-" + CALL_SEQUENCE.incrementAndGet();
        String arguments = arguments(joinPoint);
        log.info("phase=START tool={} callId={} args={}", tool.name(), callId, arguments);
        long startedAt = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            log.info("phase=END tool={} callId={} status={} durationMs={}",
                    tool.name(), callId, status(result), elapsedMillis(startedAt));
            return result;
        } catch (Throwable error) {
            log.error("phase=END tool={} callId={} status=ERROR durationMs={} error={}: {}",
                    tool.name(), callId, elapsedMillis(startedAt),
                    error.getClass().getSimpleName(), error.getMessage(), error);
            throw error;
        }
    }

    /** 参数名 → 参数值 转成 JSON；参数名缺失时退回 arg0、arg1。 */
    private String arguments(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] values = joinPoint.getArgs();
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            String name = names != null && i < names.length ? names[i] : "arg" + i;
            arguments.put(name, values[i]);
        }
        return abbreviate(toJson(arguments));
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception error) {
            // 序列化失败不能影响工具本身，退回 toString
            return String.valueOf(arguments);
        }
    }

    /**
     * 工具返回体统一是 Map（{@code SportHealthTools} 的方法签名已固定为 {@code Map<String, Object>}），
     * 把里面的 status 提到日志里，便于直接统计成功/无数据/未找到。
     *
     * <p>{@code joinPoint.proceed()} 只能拿到 {@code Object}，所以仍需 instanceof 兜底：
     * 切的是所有标注 {@code @McpTool} 的方法，不保证返回体一定带 status。
     */
    private static String status(Object result) {
        if (result instanceof Map<?, ?> body && body.get("status") != null) {
            return String.valueOf(body.get("status"));
        }
        return result == null ? "NULL" : "OK";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String abbreviate(String text) {
        String singleLine = BLANKS.matcher(text).replaceAll(" ");
        return singleLine.length() <= MAX_ARGUMENT_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_ARGUMENT_LENGTH) + "...(total " + singleLine.length() + " chars)";
    }
}
