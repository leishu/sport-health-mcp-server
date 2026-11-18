package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 解析华为「健康明细」导出（{@code Health detail data}）。
 *
 * <p>每个文件是一个 JSON 数组，元素是「一天/一次测量」级别的记录，真正的数值在
 * {@code samplePoints} 里：一条睡眠记录会拆成深睡、浅睡、REM、清醒、午睡五段，
 * 每段一个采样点。这里把每个采样点摊平成一行 {@link MetricSample}，不做任何聚合——
 * 聚合口径（按天、按次）属于查询层的事，原始粒度先保住。
 *
 * <p>{@code value} 是字符串，可能是裸数字（"55.0"）也可能是小 JSON
 * （{@code {"bpm":55}}、{@code {"BLOOD_PRESSURE_SYSTOLIC":119,"BLOOD_PRESSURE_DIASTOLIC":78}}）。
 * 抽数值的规则：恰好一个数字字段就用它，否则按 {@link #PRIMARY_FIELDS} 的优先顺序找；
 * 都找不到时 {@code value_num} 留空，原文照样进 {@code value_text}，不丢信息。
 *
 * <p>用流式解析 + 回调，而不是返回 List：单个文件最多 8.8 万条记录，全部驻留内存没有意义，
 * 调用方需要的是「边读边写」。
 */
@Component
public class HealthDetailParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 同一份数据的多种写法统一成一个规范键。
     *
     * <p>华为把心率同时存在 {@code type=7}（手机端）、{@code type=500023}（健康平台）里，
     * {@code recordId} 与采样时刻完全相同，只有 {@code key} 和 {@code value} 的包装不同。
     * 不归一的话 {@code health_metric} 主键挡不住，同一分钟心率会存两行，
     * 之后任何 {@code SUM}/{@code COUNT} 都会翻倍。
     *
     * <p>静息心率尤其隐蔽：{@code type=7} 除了标准的 {@code DATA_POINT_REST_HEARTRATE}
     * 还会上报 {@code DATA_POINT_NEW_REST_HEARTRATE}，两者与 {@code type=500024} 的
     * {@code RESTING_HEART_RATE} 时间戳、数值完全一致，必须一并归一。
     */
    private static final Map<String, String> CANONICAL_KEYS = Map.of(
            "DATA_POINT_DYNAMIC_HEARTRATE", "DYNAMIC_HEART_RATE",
            "DATA_POINT_REST_HEARTRATE", "RESTING_HEART_RATE",
            "DATA_POINT_NEW_REST_HEARTRATE", "RESTING_HEART_RATE",
            "STRESS_DATA", "STRESS");

    /** {@code value_text} 的截断长度，避免个别记录把行撑到几十 KB。 */
    private static final int VALUE_TEXT_LIMIT = 2000;

    /**
     * 多字段 JSON 里「主数值」的优先顺序。
     *
     * <p>顺序有意义：{@code bodyWeight} 排在 {@code bodyFatRate} 前面，
     * 是因为体重体脂记录的主指标是体重；血压记录取收缩压，舒张压仍保留在原文里。
     */
    private static final List<String> PRIMARY_FIELDS = List.of(
            "bpm",
            "heartRateVariabilityRMSSD",
            "oldRestBpm",
            "spo2",
            "avgSaturation",
            "BLOOD_PRESSURE_SYSTOLIC",
            "bodyWeight",
            "basalMetabolism",
            "vo2Max",
            "osaLevel",
            "afibPercent",
            "stress",
            "score",
            "altitude",
            "emotionStatus",
            "recoveryStatus",
            "exerciseType",
            "isActive");

    /**
     * @param fileName     来源文件名，仅用于异常信息
     * @param input        JSON 输入流
     * @param fallbackZone 记录时区不可用时的兜底时区
     * @param sink         每个采样点回调一次
     */
    public void parse(String fileName, InputStream input, ZoneId fallbackZone, Consumer<MetricSample> sink) {
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new HuaweiDataException(fileName + ": 顶层不是 JSON 数组");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                emit(fileName, MAPPER.readTree(parser), fallbackZone, sink);
            }
        } catch (IOException e) {
            throw new HuaweiDataException(fileName + ": 解析健康明细失败 -> " + e.getMessage(), e);
        }
    }

    private void emit(String fileName, JsonNode record, ZoneId fallbackZone, Consumer<MetricSample> sink) {
        JsonNode points = record.path("samplePoints");
        if (!points.isArray() || points.isEmpty()) {
            return;
        }
        int typeCode = record.path("type").asInt(0);
        String recordId = record.path("recordId").asText("");
        if (recordId.isBlank()) {
            return;
        }
        ZoneId zone = MotionPathParser.zoneOf(record.path("timeZone").asText(null), fallbackZone);
        String deviceCode = record.path("deviceCode").asText(null);
        String timeZone = record.path("timeZone").asText(null);
        Long recordStart = millis(record.path("startTime"));
        Long recordEnd = millis(record.path("endTime"));
        for (JsonNode point : points) {
            String key = point.path("key").asText("");
            if (key.isBlank()) {
                continue;
            }
            String metricKey = CANONICAL_KEYS.getOrDefault(key, key);
            Long start = millis(point.path("startTime"));
            Long end = millis(point.path("endTime"));
            long from = start != null ? start : recordStart == null ? 0L : recordStart;
            long to = end != null ? end : recordEnd == null ? from : recordEnd;
            String valueText = point.path("value").asText("");
            sink.accept(new MetricSample(
                    recordId,
                    metricKey,
                    typeCode,
                    localDateTime(from, zone),
                    localDateTime(to, zone),
                    extractNumber(valueText),
                    truncate(valueText),
                    point.path("unit").asText(null),
                    deviceCode,
                    timeZone));
        }
    }

    /**
     * 从 {@code value} 里抽主数值。
     *
     * @return 无法确定时返回 null，调用方保留 {@code value_text}
     */
    static Double extractNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (!text.startsWith("{")) {
            return MotionTrackData.number(text);
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (IOException e) {
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        JsonNode onlyNumber = null;
        int numericFields = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isNumber()) {
                numericFields++;
                onlyNumber = field.getValue();
            }
        }
        if (numericFields == 1) {
            return onlyNumber.asDouble();
        }
        for (String name : PRIMARY_FIELDS) {
            JsonNode candidate = node.get(name);
            if (candidate != null && candidate.isNumber()) {
                return candidate.asDouble();
            }
        }
        return null;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= VALUE_TEXT_LIMIT ? value : value.substring(0, VALUE_TEXT_LIMIT);
    }

    private static LocalDateTime localDateTime(long epochMillis, ZoneId zone) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone);
    }

    private static Long millis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long value = node.asLong(0);
        return value <= 0 ? null : value;
    }
}
