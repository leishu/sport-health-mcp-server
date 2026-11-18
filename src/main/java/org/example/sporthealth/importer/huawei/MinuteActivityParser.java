package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 解析华为「每分钟运动数据」导出（{@code sport per minute merged data}）。
 *
 * <p>文件是一个按天排列的数组，每天下面挂着 {@code sportDataUserData} 列表，
 * 一条记录通常覆盖一分钟。这里同样边读边回调，不把 10 万行攒在内存里。
 *
 * <p>热量字段的单位是 cal（卡，4070 = 4.07 kcal），落库时换算成千卡。
 */
@Component
public class MinuteActivityParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param fileName     来源文件名，仅用于异常信息
     * @param input        JSON 输入流
     * @param fallbackZone 时区兜底
     * @param sink         每条分钟记录回调一次
     */
    public void parse(String fileName, InputStream input, ZoneId fallbackZone, Consumer<MinuteActivityRow> sink) {
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new HuaweiDataException(fileName + ": 顶层不是 JSON 数组");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode day = MAPPER.readTree(parser);
                emitDay(fileName, day, fallbackZone, sink);
            }
        } catch (IOException e) {
            throw new HuaweiDataException(fileName + ": 解析每分钟运动数据失败 -> " + e.getMessage(), e);
        }
    }

    private void emitDay(String fileName, JsonNode day, ZoneId fallbackZone, Consumer<MinuteActivityRow> sink) {
        int recordDay = day.path("recordDay").asInt(0);
        ZoneId zone = MotionPathParser.zoneOf(day.path("timeZone").asText(null), fallbackZone);
        JsonNode rows = day.path("sportDataUserData");
        if (!rows.isArray()) {
            return;
        }
        // 手机与手表会各自上报同一分钟：跳过重复会让「一天走了多少步」直接翻倍。
        // 同一 (分钟, 运动类型) 只留步数较大的那条，等价于「以记录更完整的那台设备为准」，
        // 同时保证结果与文件内顺序无关（换一次导出顺序不会变数字）。
        Map<String, MinuteActivityRow> unique = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            MinuteActivityRow parsed = toRow(row, recordDay, zone);
            if (parsed == null) {
                continue;
            }
            String key = parsed.startTime() + "|" + parsed.sportType();
            MinuteActivityRow existing = unique.get(key);
            if (existing == null || steps(parsed) > steps(existing)) {
                unique.put(key, parsed);
            }
        }
        unique.values().forEach(sink);
    }

    private MinuteActivityRow toRow(JsonNode row, int recordDay, ZoneId zone) {
        Long start = millis(row.path("startTime"));
        if (start == null) {
            return null;
        }
        Long end = millis(row.path("endTime"));
        JsonNode basic = row.path("sportBasicInfos").path(0);
        return new MinuteActivityRow(
                localDateTime(start, zone),
                localDateTime(end == null ? start : end, zone),
                row.path("sportType").asInt(0),
                recordDay,
                intOrNull(basic, "steps"),
                intOrNull(basic, "distance"),
                basic.hasNonNull("calorie") ? basic.path("calorie").asDouble() / 1000d : null,
                intOrNull(basic, "duration"),
                intOrNull(basic, "isIntensity"),
                intOrNull(basic, "isStand"),
                basic.hasNonNull("altitude") ? basic.path("altitude").asDouble() : null,
                row.path("deviceCode").asText(null));
    }

    private static int steps(MinuteActivityRow row) {
        return row.steps() == null ? 0 : row.steps();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asInt();
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
