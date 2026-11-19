package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.example.sporthealth.domain.TrackPoint;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 解析华为「运动轨迹」导出文件（{@code Motion path detail data}）。
 *
 * <p>一个文件是一个 JSON 数组，元素为一次运动：{@code summaryData} 是汇总指标，
 * {@code detailData} 是逐点轨迹（{@link MotionTrackData} 负责解析）。
 *
 * <p>用流式 {@link JsonParser} 逐个元素读取，不把整个数组装进内存：单个文件最大 9 MB，
 * 但每条的 {@code detailData} 就有几十万字符，一次性反序列化会白白撑大堆占用。
 *
 * <p>坐标系：导出标注为 GCJ-02，这里统一转成 WGS-84 再交出去，理由见 {@link Gcj02}。
 */
@Component
public class MotionPathParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 华为运动记录的类型码，恒定 30001。 */
    private static final int MOTION_TYPE = 30001;

    /** 气压高度噪声容差：小于 1.5 米的高差视为原地抖动，不计入爬升。 */
    private static final double CLIMB_THRESHOLD_METERS = 1.5;

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    /**
     * @param fileName     来源文件名，仅用于异常信息
     * @param input        JSON 输入流
     * @param fallbackZone 记录自带时区不可用时的兜底时区
     */
    public List<ParsedWorkout> parse(String fileName, InputStream input, ZoneId fallbackZone) {
        List<ParsedWorkout> workouts = new ArrayList<>();
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new HuaweiDataException(fileName + ": 顶层不是 JSON 数组");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode node = MAPPER.readTree(parser);
                ParsedWorkout workout = toWorkout(fileName, node, fallbackZone);
                if (workout != null) {
                    workouts.add(workout);
                }
            }
        } catch (IOException e) {
            throw new HuaweiDataException(fileName + ": 解析运动轨迹失败 -> " + e.getMessage(), e);
        }
        return workouts;
    }

    private ParsedWorkout toWorkout(String fileName, JsonNode node, ZoneId fallbackZone) {
        int typeCode = node.path("type").asInt(0);
        if (typeCode != 0 && typeCode != MOTION_TYPE) {
            return null;
        }
        Long startMillis = millis(node.path("startTime"));
        if (startMillis == null) {
            throw new HuaweiDataException(fileName + ": 运动记录缺少 startTime");
        }
        Long endMillis = millis(node.path("endTime"));
        if (endMillis == null) {
            endMillis = startMillis;
        }
        ZoneId zone = zoneOf(node.path("timeZone").asText(null), fallbackZone);
        String summaryText = node.path("summaryData").asText("{}");
        JsonNode summary = json(summaryText);
        MotionTrackData track = MotionTrackData.parse(node.path("detailData").asText(""));
        List<TrackPoint> points = track.toTrackPoints(zone);

        int subType = node.path("subType").asInt(0);
        String sport = sportName(subType);
        String family = sportFamily(subType);
        String recordId = node.path("recordId").asText("");
        long dataId = node.path("dataId").asLong(0);
        String id = "hw-" + LocalDateTime.ofInstant(Instant.ofEpochMilli(startMillis), zone).format(ID_TIME)
                + "-" + dataId;

        double durationSeconds = summary.path("totalTime").asDouble(0) / 1000d;
        if (durationSeconds <= 0) {
            durationSeconds = Math.max(0, (endMillis - startMillis) / 1000d);
        }
        double distanceMeters = summary.path("totalDistance").asDouble(0);
        // 官方字段说明：totalCalories 单位是 cal（卡），362000 = 362 kcal，落库统一成千卡
        double caloriesKcal = summary.path("totalCalories").asDouble(0) / 1000d;
        int totalSteps = summary.path("totalSteps").asInt(0);

        Double climb = climbOf(points);
        // mTotalDescent 单位是分米，除以 10 换成米；轨迹累加那条路本来就是米，不再换算
        Double descent = summary.hasNonNull("mTotalDescent")
                ? summary.path("mTotalDescent").asDouble() / 10d
                : descentOf(points);

        return new ParsedWorkout(
                id,
                recordId,
                Instant.ofEpochMilli(startMillis),
                Instant.ofEpochMilli(endMillis),
                typeCode,
                subType,
                summary.path("sportType").asInt(0),
                sport,
                family,
                distanceMeters,
                durationSeconds,
                caloriesKcal,
                totalSteps,
                intOrNull(summary, "avgHeartRate"),
                intOrNull(summary, "maxHeartRate"),
                intOrNull(summary, "minHeartRate"),
                intOrNull(summary, "avgStepRate"),
                climb,
                descent,
                points,
                summaryText,
                track.streams());
    }

    /**
     * 累计爬升：按气压高度序列累加正向高差。
     *
     * <p>不能用「最高 - 最低」：一次折返跑会把这个值算成两倍。
     * 也不能不设阈值：气压计在地铁里会抖出几十米假爬升。
     */
    static Double climbOf(List<TrackPoint> points) {
        return accumulate(points, true);
    }

    /** 累计下降，口径与 {@link #climbOf} 对称。 */
    static Double descentOf(List<TrackPoint> points) {
        return accumulate(points, false);
    }

    private static Double accumulate(List<TrackPoint> points, boolean climb) {
        Double anchor = null;
        double total = 0;
        boolean seen = false;
        for (TrackPoint point : points) {
            Double altitude = point.altitudeMeters();
            if (altitude == null) {
                continue;
            }
            if (anchor == null) {
                anchor = altitude;
                seen = true;
                continue;
            }
            double delta = altitude - anchor;
            if (climb && delta >= CLIMB_THRESHOLD_METERS) {
                total += delta;
                anchor = altitude;
            } else if (!climb && delta <= -CLIMB_THRESHOLD_METERS) {
                total += -delta;
                anchor = altitude;
            } else if (Math.abs(delta) < CLIMB_THRESHOLD_METERS) {
                // 小幅抖动：锚点跟着走，避免噪声累积成假爬升
                anchor = altitude;
            }
        }
        return seen ? Math.round(total * 10) / 10d : null;
    }

    /** subType 语义：4=户外跑步、5=户外步行、112/283=室内及其它。 */
    static String sportName(int subType) {
        return switch (subType) {
            case 4 -> "户外跑步";
            case 5 -> "户外步行";
            case 112 -> "室内运动";
            case 283 -> "其它运动";
            default -> "运动(subType=" + subType + ")";
        };
    }

    /** 只有跑步进入 {@code sport_family='RUN'}，步行与其它运动仍入库但不参与跑步统计。 */
    static String sportFamily(int subType) {
        return switch (subType) {
            case 4 -> "RUN";
            case 5 -> "WALK";
            default -> "OTHER";
        };
    }

    static ZoneId zoneOf(String timeZone, ZoneId fallback) {
        if (timeZone == null || timeZone.isBlank()) {
            return fallback;
        }
        String normalized = timeZone.trim();
        if (normalized.length() == 5 && (normalized.charAt(0) == '+' || normalized.charAt(0) == '-')) {
            try {
                int hours = Integer.parseInt(normalized.substring(1, 3));
                int minutes = Integer.parseInt(normalized.substring(3, 5));
                int sign = normalized.charAt(0) == '-' ? -1 : 1;
                return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        try {
            return ZoneId.of(normalized);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static Long millis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long value = node.asLong(0);
        return value <= 0 ? null : value;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber() || value.asInt() <= 0) {
            return null;
        }
        return value.asInt();
    }
}
