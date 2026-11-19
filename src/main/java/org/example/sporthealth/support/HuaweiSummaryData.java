package org.example.sporthealth.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 华为 {@code summaryData} 原文的解析、字段裁剪与单位换算。
 *
 * <p>库里存的是华为 App 导出的原始 JSON（约 2.5 KB），字段名是驼峰混拼音式的内部命名，
 * 单位五花八门（毫秒、卡、分米、Float 哨兵值）。工具层如果直接把这段字符串丢给模型，
 * 每次都要重新猜单位；这里把已经实测确认的字段摊平成带单位与文本镜像的结构，
 * 剩下没建模的字段仍原样保留，靠 {@code fields} 按需取。
 *
 * <p>只做「确认过」的换算，不做猜测：{@code britishPaceMap}/{@code bpm2} 这类
 * 数值恒等于 {@code pm2} 加一个 204~224 秒偏移的字段，原样留着并给一条 note，
 * 不冒充配速。
 */
public final class HuaweiSummaryData {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    /**
     * 华为没填的 Float 字段会写成 {@code Float.MAX_VALUE} / {@code Float.MIN_VALUE}，
     * 也就是未初始化时的默认值。直接展示会得到「海拔 3.4e38 米」这种明显的假数据。
     */
    private static final double SENTINEL_MAX = 3.4028234663852886E38d;
    private static final double SENTINEL_MIN = 1.401298464324817E-45d;

    /** {@code paceMap} / {@code britishPaceMap} 的键 = 距离米 × 10000 + 轨迹点序号。 */
    private static final long PACE_KEY_FACTOR = 10_000L;

    /** 心率区间的展示顺序：从低强度到高强度。 */
    private static final List<String> ZONE_ORDER =
            List.of("warmUp", "fitness", "fatBurn", "aerobic", "anaerobic");

    private HuaweiSummaryData() {
    }

    /** 解析原文；空白或非法 JSON 返回空 Map，由调用方决定怎么提示。 */
    public static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JSON.readValue(text, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 按 {@code fields} 裁剪原始字段，支持 {@code a.b} 与 {@code a.*}；不传表示全给。
     *
     * @param missing 表达式里没匹配到的路径会记在这里，而不是静默返回空对象
     */
    public static Map<String, Object> select(Map<String, Object> raw, String fields, List<String> missing) {
        if (fields == null || fields.isBlank()) {
            return raw;
        }
        String expression = fields.trim();
        if (expression.equals("*") || expression.equalsIgnoreCase("all")) {
            return raw;
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String token : expression.split(",")) {
            String path = token.trim();
            if (!path.isEmpty()) {
                putPath(raw, path, selected, missing);
            }
        }
        return selected;
    }

    /**
     * 已知字段的单位换算结果。取不到的字段直接不出现，不用 {@code null} 占位。
     *
     * @param notes 单位陷阱与哨兵值的说明，面向读结果的人（或模型）
     */
    public static Map<String, Object> decode(Map<String, Object> raw, List<String> notes) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        Map<String, Object> extend = map(raw.get("mExtendTrackDataMap"));
        Map<String, Object> wear = map(raw.get("wearSportData"));

        Double durationSeconds = round(number(raw.get("totalTime")) == null
                ? null
                : number(raw.get("totalTime")) / 1000d, 1);
        Double distanceMeters = number(raw.get("totalDistance"));
        Double calories = kcal(raw.get("totalCalories"));
        if (durationSeconds != null) {
            decoded.put("durationSeconds", durationSeconds);
            decoded.put("durationText", PaceFormat.clock(durationSeconds));
        }
        if (distanceMeters != null) {
            decoded.put("distanceMeters", distanceMeters);
            decoded.put("distanceKm", round(distanceMeters / 1000d, 2));
        }
        if (calories != null) {
            decoded.put("caloriesKcal", calories);
            Double active = kcal(extend.get("activeCalorie"));
            if (active != null) {
                decoded.put("activeCaloriesKcal", active);
            }
        }
        putPace(decoded, "avgPace", raw.get("avgPace"));
        putPace(decoded, "bestPace", raw.get("bestPace"));

        Double descent = number(raw.get("mTotalDescent"));
        if (descent != null) {
            // 落库时除以 10 才得到米，原始单位是分米
            decoded.put("descentMeters", round(descent / 10d, 1));
        }

        Map<String, Object> heartRate = compact(
                "avg", integer(raw.get("avgHeartRate")),
                "max", integer(raw.get("maxHeartRate")),
                "min", positiveInteger(raw.get("minHeartRate")));
        if (!heartRate.isEmpty()) {
            decoded.put("heartRate", heartRate);
        }
        Map<String, Object> cadence = compact(
                "avg", integer(raw.get("avgStepRate")),
                "max", integer(raw.get("bestStepRate")),
                "totalSteps", integer(raw.get("totalSteps")));
        if (!cadence.isEmpty()) {
            decoded.put("cadence", cadence);
        }

        List<Map<String, Object>> splits = kmSplits(raw);
        if (!splits.isEmpty()) {
            decoded.put("kmSplits", splits);
        }
        List<Map<String, Object>> zones = heartRateZones(raw);
        if (!zones.isEmpty()) {
            decoded.put("heartRateZones", zones);
        }
        Map<String, Object> posture = posture(raw, extend);
        if (!posture.isEmpty()) {
            decoded.put("posture", posture);
        }
        Map<String, Object> training = training(wear, extend);
        if (!training.isEmpty()) {
            decoded.put("training", training);
        }
        Map<String, Object> deviceWeather = deviceWeather(extend);
        if (!deviceWeather.isEmpty()) {
            decoded.put("deviceWeather", deviceWeather);
        }

        notes.addAll(notes(raw, extend));
        return decoded;
    }

    /**
     * 配速字段：华为给的是秒/公里，同时给出 {@code 5'46"/km} 文本。
     * 键名统一成 {@code avgPaceSecondsPerKm} / {@code avgPaceText} 这种形状。
     */
    private static void putPace(Map<String, Object> target, String prefix, Object value) {
        Double seconds = number(value);
        if (seconds == null || seconds <= 0) {
            return;
        }
        Double rounded = round(seconds, 1);
        target.put(prefix + "SecondsPerKm", rounded);
        target.put(prefix + "Text", PaceFormat.pace(rounded));
    }

    /**
     * 单公里分段：{@code paceMap} 每行是「到该点为止的这一公里用了多少秒」。
     *
     * <p>末段不足一公里，用 {@code 配速 × 段长} 折算用时；累计时间自己累加，
     * 不依赖 {@code partTimeMap}（两者在整公里段上重合，但末段 partTimeMap 缺项）。
     */
    private static List<Map<String, Object>> kmSplits(Map<String, Object> raw) {
        Map<String, Object> paceMap = map(raw.get("paceMap"));
        if (paceMap.isEmpty()) {
            return List.of();
        }
        List<Long> keys = new ArrayList<>(paceMap.size());
        for (String key : paceMap.keySet()) {
            try {
                keys.add(Long.parseLong(key.trim()));
            } catch (NumberFormatException e) {
                // 键不是数字说明数据结构变了，忽略这一行，不让整次解析失败
            }
        }
        keys.sort(Comparator.naturalOrder());

        List<Map<String, Object>> splits = new ArrayList<>(keys.size());
        double previousKm = 0d;
        double cumulative = 0d;
        int index = 1;
        for (long key : keys) {
            Double pace = number(paceMap.get(String.valueOf(key)));
            if (pace == null || pace <= 0) {
                continue;
            }
            double km = round((key / PACE_KEY_FACTOR) / 1000d, 4);
            double distanceKm = km - previousKm;
            if (distanceKm <= 1e-4) {
                continue;
            }
            double seconds = pace * distanceKm;
            cumulative += seconds;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("endKm", km);
            row.put("distanceKm", round(distanceKm, 3));
            row.put("paceSecondsPerKm", round(pace, 1));
            row.put("paceText", PaceFormat.pace(pace));
            row.put("durationSeconds", round(seconds, 1));
            row.put("cumulativeSeconds", round(cumulative, 1));
            row.put("cumulativeText", PaceFormat.clock(cumulative));
            row.put("pointIndex", (int) (key % PACE_KEY_FACTOR));
            row.put("partial", distanceKm < 0.999d);
            splits.add(row);
            previousKm = km;
        }
        return splits;
    }

    /** {@code heartRateZoneStat} 是「被转义成字符串的 JSON」，各区间单位是秒。 */
    private static List<Map<String, Object>> heartRateZones(Map<String, Object> raw) {
        Object stat = raw.get("heartRateZoneStat");
        if (stat == null) {
            return List.of();
        }
        Map<String, Object> zones = stat instanceof Map<?, ?>
                ? map(stat)
                : map(parse(String.valueOf(stat)));
        if (zones.isEmpty()) {
            return List.of();
        }
        double total = zones.values().stream()
                .map(HuaweiSummaryData::number)
                .filter(value -> value != null && value > 0)
                .mapToDouble(Double::doubleValue)
                .sum();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String zone : ZONE_ORDER) {
            Double seconds = number(zones.get(zone));
            if (seconds == null || seconds <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zone", zone);
            row.put("seconds", round(seconds, 0));
            row.put("text", PaceFormat.clock(seconds));
            row.put("percent", total > 0 ? round(seconds * 100d / total, 1) : null);
            rows.add(row);
        }
        return rows;
    }

    /**
     * 跑姿汇总。{@code -1} / {@code -101} 是「该机型或该次运动没采集」的占位，
     * 一律转成缺省，避免把 -101 毫米当成数据读。
     */
    private static Map<String, Object> posture(Map<String, Object> raw, Map<String, Object> extend) {
        Map<String, Object> posture = compact(
                "groundContactTimeMs", positive(raw.get("mAvgGroundContactTime")),
                "verticalOscillationCm", positive(extend.get("avg_v_osc")),
                "groundBalancePercent", positive(extend.get("avg_gc_tb")),
                "hangTimeMs", positive(raw.get("mAverageHangTime")),
                "hangTimeRate", positive(raw.get("mGroundHangTimeRate")),
                "swingAngleDeg", positive(raw.get("mAvgSwingAngle")),
                "eversionExcursion", positive(raw.get("mAvgEversionExcursion")),
                "groundImpactAcceleration", positive(raw.get("mAvgGroundImpactAcceleration")));
        return posture;
    }

    /** 训练效果：华为把 TE 放大 10 倍存整数（26 表示 2.6），恢复时间单位是秒。 */
    private static Map<String, Object> training(Map<String, Object> wear, Map<String, Object> extend) {
        Map<String, Object> training = new LinkedHashMap<>();
        Double te = number(wear.get("etraining_effect"));
        if (te != null) {
            training.put("aerobicTrainingEffect", round(te / 10d, 1));
        }
        Double anaerobic = number(wear.get("anaerobic_exercise_etraining_effect"));
        if (anaerobic != null) {
            training.put("anaerobicTrainingEffect", round(anaerobic / 10d, 1));
        }
        Double recovery = number(wear.get("recovery_time"));
        if (recovery != null && recovery > 0) {
            training.put("recoveryTimeMinutes", round(recovery / 60d, 0));
        }
        Double loadPeak = number(wear.get("load_peak"));
        if (loadPeak != null) {
            training.put("loadPeak", loadPeak);
        }
        putIfPresent(training, "aerobicLoadPeak", number(extend.get("aerobicLoadPeak")));
        putIfPresent(training, "anaerobicLoadPeak", number(extend.get("anaerobicLoadPeak")));
        putIfPresent(training, "trainingPoints", number(extend.get("trainPoint")));
        putIfPresent(training, "achievePercent", number(wear.get("achieve_percent")));
        return training;
    }

    /**
     * 华为手机自己记的天气（只有部分机型、部分记录带）。
     *
     * <p>与 {@code run_weather}（open-meteo 回填）不是一回事：这里量的是手机所在位置的体感，
     * 拿来交叉验证回填值是否离谱比当作标准答案更合适。
     */
    private static Map<String, Object> deviceWeather(Map<String, Object> extend) {
        return compact(
                "temperatureC", number(extend.get("temperature")),
                "apparentTemperatureC", number(extend.get("apparentTemperature")),
                "humidityPercent", number(extend.get("relativeHumidity")),
                "windSpeed", number(extend.get("windSpeed")),
                "windForce", number(extend.get("windForce")),
                "windDirectionDegrees", number(extend.get("windDirection")),
                "weatherCode", number(extend.get("weather")));
    }

    /** 只把「确实会误读」的点写出来，不做字段百科。 */
    private static List<String> notes(Map<String, Object> raw, Map<String, Object> extend) {
        List<String> notes = new ArrayList<>();
        if (hasSentinel(raw.get("mMinAlti")) || hasSentinel(raw.get("mMaxAlti"))) {
            notes.add("mMinAlti/mMaxAlti 是 Float 未初始化的哨兵值（3.4e38 / 1.4e-45），不是海拔；"
                    + "海拔请用 get_run_detail 的 activity.minAltitudeMeters / maxAltitudeMeters");
        }
        if (raw.containsKey("totalTime") || raw.containsKey("totalCalories")) {
            notes.add("原文 totalTime 单位毫秒、totalCalories 单位卡，decoded 已换算成秒与千卡");
        }
        if (raw.containsKey("paceMap")) {
            notes.add("paceMap/britishPaceMap 的键是「距离米 × 10000 + 轨迹点序号」，"
                    + "值是该段的秒/公里；kmSplits 已按此拆开");
        }
        if (raw.containsKey("britishPaceMap")) {
            notes.add("britishPaceMap 与 britishPartTimeMap 的数值恒等于同段 pm2 加 204~224 秒的偏移"
                    + "（即 track_stream 里的 bpm2），语义未确认，不参与配速换算");
        }
        if (raw.containsKey("mAverageHangTime") || extend.containsKey("avg_v_osc")) {
            notes.add("跑姿字段的 -1 / -101 表示该机型未采集，已置为 null；"
                    + "触地时间/垂直振幅/左右平衡需要连接跑步动态传感器才有");
        }
        if (extend.containsKey("weather")) {
            notes.add("deviceWeather 是手机自己记的天气，weatherCode 用的是华为的编码，"
                    + "与 run_weather（open-meteo）的 weatherCode 不是一套，不要混用");
        }
        return notes;
    }

    private static boolean hasSentinel(Object value) {
        Double number = number(value);
        return number != null && (Math.abs(number - SENTINEL_MAX) < 1e-6 || Math.abs(number - SENTINEL_MIN) < 1e-6);
    }

    private static void putPath(Map<String, Object> raw, String path, Map<String, Object> out, List<String> missing) {
        String[] parts = path.split("\\.");
        Map<String, Object> cursor = out;
        Object node = raw;
        for (int i = 0; i < parts.length; i++) {
            if (!(node instanceof Map<?, ?> current)) {
                missing.add(path);
                return;
            }
            String part = parts[i];
            boolean last = i == parts.length - 1;
            if (part.equals("*")) {
                // a.* ：把剩下的整块拿过来
                map(current).forEach(cursor::put);
                return;
            }
            String key = findKey(current, part);
            if (key == null) {
                missing.add(path);
                return;
            }
            Object value = current.get(key);
            if (last) {
                cursor.put(key, value);
                return;
            }
            if (!(value instanceof Map<?, ?> child)) {
                missing.add(path);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> childOut =
                    (Map<String, Object>) cursor.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            cursor = childOut;
            node = child;
        }
    }

    /** 顶层键大小写不敏感（模型偶尔写成 totalTime / totaltime）。 */
    private static String findKey(Map<?, ?> source, String wanted) {
        for (Object key : source.keySet()) {
            if (key != null && key.toString().equalsIgnoreCase(wanted)) {
                return key.toString();
            }
        }
        return null;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                copy.put(key.toString(), item);
            }
        });
        return copy;
    }

    /** 卡 → 千卡。华为的 {@code totalCalories} / {@code activeCalorie} 都是卡。 */
    private static Double kcal(Object value) {
        Double number = number(value);
        return number == null ? null : round(number / 1000d, 1);
    }

    private static Double number(Object value) {
        if (value instanceof Number n) {
            double asDouble = n.doubleValue();
            return Double.isFinite(asDouble) ? asDouble : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double parsed = Double.parseDouble(text.trim());
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Integer integer(Object value) {
        Double number = number(value);
        return number == null ? null : (int) Math.round(number);
    }

    /** 负数（含 -1/-101 占位）与 0 一律视为缺省：心率 0 不可能是有效值。 */
    private static Integer positiveInteger(Object value) {
        Integer number = integer(value);
        return number == null || number <= 0 ? null : number;
    }

    private static Double positive(Object value) {
        Double number = number(value);
        if (number == null || number < 0) {
            return null;
        }
        return Math.abs(number) < 1e-6 ? null : number;
    }

    private static Double round(Double value, int scale) {
        return value == null ? null : PaceFormat.round(value, scale);
    }

    /** 依次放入非空键值；null 的不占位，避免返回体里一堆 null。 */
    private static Map<String, Object> compact(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object key = pairs[i];
            Object value = pairs[i + 1];
            if (key != null && value != null) {
                map.put(key.toString(), value);
            }
        }
        return map;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Double value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
