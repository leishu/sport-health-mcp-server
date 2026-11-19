package org.example.sporthealth.importer.huawei;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.sporthealth.domain.TrackPoint;

/**
 * 华为轨迹记录里 {@code detailData} 的解析结果。
 *
 * <p>这个字段不是 JSON，而是一段自定义文本协议，一行一个采样点，形如：
 * <pre>
 * tp=lbs;k=0;lat=39.905708;lon=116.236651;alt=54.55908203125;t=1.769902015478E12;
 * tp=h-r;k=1769902023331;v=78;
 * tp=s-r;k=1769902014291;v=201;
 * tp=alt;k=1769902021000;v=58.79999923706055;
 * </pre>
 * 字段含义：
 * <ul>
 *   <li>{@code lbs}：经纬度点，{@code k} 是点序号，{@code t} 是毫秒时间戳；</li>
 *   <li>{@code h-r}：心率，{@code k} 是毫秒时间戳，{@code v} 是 bpm；</li>
 *   <li>{@code s-r}：步频，{@code k} 是毫秒时间戳，{@code v} 是步/分；</li>
 *   <li>{@code alt}：气压计高度，{@code k} 是毫秒时间戳（比 GPS 高度平滑得多）；</li>
 *   <li>{@code dis}：累计距离，{@code k} 与 {@code lbs} 的点序号对齐；</li>
 *   <li>{@code rs}：每 5 秒速度、{@code rp} 跑姿、{@code pm2}/{@code bpm2} 单公里配速等，
 *       不属于逐点轨迹，原样收进 {@link MotionStream}，由写入层落到 {@code track_stream}。</li>
 * </ul>
 *
 * <p>心率、步频、气压高度与轨迹点各自独立采样，因此按时间就近匹配：心率与高度容差 30 秒，
 * 步频采样稀疏（约 40 秒一个）容差放大到 120 秒。超出容差视为缺失，不做外推。
 */
public final class MotionTrackData {

    /** 气压高度与心率的匹配容差。 */
    private static final long SAMPLE_TOLERANCE_MILLIS = 30_000L;

    /** 步频采样稀疏，容差放宽。 */
    private static final long CADENCE_TOLERANCE_MILLIS = 120_000L;

    /** 华为表示「此处无定位」的哨兵值。 */
    private static final double SENTINEL_LATITUDE = 90.0;
    private static final double SENTINEL_LONGITUDE = -80.0;

    private final List<Position> positions;
    private final List<Sample> heartRates;
    private final List<Sample> cadences;
    private final List<Sample> altitudes;
    private final Map<Integer, Double> distanceByIndex;
    private final List<MotionStream> streams;

    private MotionTrackData(List<Position> positions,
                            List<Sample> heartRates,
                            List<Sample> cadences,
                            List<Sample> altitudes,
                            Map<Integer, Double> distanceByIndex,
                            List<MotionStream> streams) {
        this.positions = positions;
        this.heartRates = heartRates;
        this.cadences = cadences;
        this.altitudes = altitudes;
        this.distanceByIndex = distanceByIndex;
        this.streams = streams;
    }

    /** 一个 GPS 点。 */
    public record Position(int index, long timeMillis, double latitude, double longitude, Double altitude) {
    }

    /** 一条「时刻 → 数值」采样。 */
    public record Sample(long timeMillis, double value) {
    }

    /**
     * 未进入 {@code track_point} 的原始流。
     *
     * <p>{@code fields} 保留该行的全部键值，序列化后就是 {@code track_stream.data}，
     * 这样新增字段（例如跑姿指标扩充）不需要改表结构。
     *
     * @param type        流类型：rs / rp / pad / pm2 / p-m / b-p-m / bpm2 / scp / i-h-r / evt，
     *                    解析不出的行记为 {@code unknown}
     * @param timeMillis  时间戳（毫秒）；该流的 k 不是时间时为 null
     * @param pointIndex  点序号或分段序号；没有时为 null
     * @param value       主数值：优先 v，其次 p；跑姿行没有单一数值
     * @param fields      原始键值对
     * @param rawLine     原始行，仅在无法拆分时保留，确保不丢数据
     */
    public record MotionStream(String type,
                               Long timeMillis,
                               Integer pointIndex,
                               Double value,
                               Map<String, String> fields,
                               String rawLine) {
    }

    public boolean hasPositions() {
        return !positions.isEmpty();
    }

    /** 解析一段 {@code detailData}；格式不认识的行走忽略，不抛异常。 */
    public static MotionTrackData parse(String detailData) {
        List<Position> positions = new ArrayList<>();
        List<Sample> heartRates = new ArrayList<>();
        List<Sample> cadences = new ArrayList<>();
        List<Sample> altitudes = new ArrayList<>();
        Map<Integer, Double> distances = new HashMap<>();
        List<MotionStream> streams = new ArrayList<>();
        if (detailData == null || detailData.isBlank()) {
            return new MotionTrackData(positions, heartRates, cadences, altitudes, distances, streams);
        }
        for (String line : detailData.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, String> fields = fields(trimmed);
            String type = fields.get("tp");
            if (type == null) {
                // DETAIL_NULL 前缀等异常行：不猜结构，原样收进 unknown 流
                streams.add(new MotionStream("unknown", null, null, null, Map.of(), trimmed));
                continue;
            }
            switch (type) {
                case "lbs" -> addPosition(fields, positions);
                case "h-r" -> addSample(fields, heartRates);
                case "s-r" -> addSample(fields, cadences);
                case "alt" -> addSample(fields, altitudes);
                case "dis" -> addDistance(fields, distances);
                default -> streams.add(toStream(type, fields, trimmed));
            }
        }
        positions.sort((left, right) -> Long.compare(left.timeMillis(), right.timeMillis()));
        heartRates.sort((left, right) -> Long.compare(left.timeMillis(), right.timeMillis()));
        cadences.sort((left, right) -> Long.compare(left.timeMillis(), right.timeMillis()));
        altitudes.sort((left, right) -> Long.compare(left.timeMillis(), right.timeMillis()));
        return new MotionTrackData(positions, heartRates, cadences, altitudes, distances, streams);
    }

    /** 未入轨迹的流：识别时间戳/序号/主数值，其余字段全部留在 fields 里。 */
    private static MotionStream toStream(String type, Map<String, String> fields, String rawLine) {
        Long timeMillis = null;
        Integer pointIndex = null;
        Double k = number(fields.get("k"));
        if (k != null) {
            if (k > 100_000_000_000d) {
                timeMillis = k.longValue();
            } else {
                pointIndex = (int) Math.round(k);
            }
        }
        if (timeMillis == null) {
            Double start = number(fields.get("st"));
            if (start != null && start > 100_000_000_000d) {
                timeMillis = start.longValue();
            }
        }
        if (pointIndex == null && ("pm2".equals(type) || "bpm2".equals(type))) {
            Double segment = number(fields.get("n"));
            pointIndex = segment == null ? null : (int) Math.round(segment);
        }
        Double value = number(fields.get("v"));
        if (value == null) {
            value = number(fields.get("p"));
        }
        return new MotionStream(type, timeMillis, pointIndex, value, Map.copyOf(fields), rawLine);
    }

    /** 未进入轨迹点的全部流，按原始顺序。 */
    public List<MotionStream> streams() {
        return streams;
    }

    /** 转成入库用的轨迹点：坐标转 WGS-84，心率/步频/高度按时间就近匹配。 */
    public List<TrackPoint> toTrackPoints(ZoneId zone) {
        List<TrackPoint> points = new ArrayList<>(positions.size());
        int heartRateCursor = 0;
        int cadenceCursor = 0;
        int altitudeCursor = 0;
        for (Position position : positions) {
            double[] wgs = Gcj02.toWgs84(position.latitude(), position.longitude());
            heartRateCursor = advance(heartRates, heartRateCursor, position.timeMillis(), SAMPLE_TOLERANCE_MILLIS);
            cadenceCursor = advance(cadences, cadenceCursor, position.timeMillis(), CADENCE_TOLERANCE_MILLIS);
            altitudeCursor = advance(altitudes, altitudeCursor, position.timeMillis(), SAMPLE_TOLERANCE_MILLIS);
            Integer heartRate = rounded(nearestValue(heartRates, heartRateCursor, position.timeMillis(),
                    SAMPLE_TOLERANCE_MILLIS));
            Integer cadence = rounded(nearestValue(cadences, cadenceCursor, position.timeMillis(),
                    CADENCE_TOLERANCE_MILLIS));
            Double barometer = nearestValue(altitudes, altitudeCursor, position.timeMillis(), SAMPLE_TOLERANCE_MILLIS);
            Double altitude = barometer != null ? barometer : position.altitude();
            Double distance = distanceByIndex.get(position.index());
            points.add(new TrackPoint(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(position.timeMillis()), zone),
                    round(wgs[0], 6),
                    round(wgs[1], 6),
                    altitude,
                    heartRate,
                    cadence,
                    distance));
        }
        return points;
    }

    /** 光标前移到第一个不早于目标时刻的采样，使后续每个点都能 O(1) 找到邻近值。 */
    private static int advance(List<Sample> samples, int cursor, long timeMillis, long tolerance) {
        int next = cursor;
        while (next < samples.size() && samples.get(next).timeMillis() < timeMillis - tolerance) {
            next++;
        }
        return next;
    }

    /** 在光标附近取时间最近的采样，超出容差返回 null。 */
    private static Double nearestValue(List<Sample> samples, int cursor, long timeMillis, long tolerance) {
        if (samples.isEmpty()) {
            return null;
        }
        Sample best = null;
        long bestGap = Long.MAX_VALUE;
        for (int i = cursor; i < samples.size(); i++) {
            Sample sample = samples.get(i);
            long gap = Math.abs(sample.timeMillis() - timeMillis);
            if (gap < bestGap) {
                best = sample;
                bestGap = gap;
            }
            if (sample.timeMillis() > timeMillis + tolerance) {
                break;
            }
        }
        return best == null || bestGap > tolerance ? null : best.value();
    }

    private static void addPosition(Map<String, String> fields, List<Position> positions) {
        Double latitude = number(fields.get("lat"));
        Double longitude = number(fields.get("lon"));
        Double time = number(fields.get("t"));
        Integer index = integer(fields.get("k"));
        if (latitude == null || longitude == null || time == null || index == null) {
            return;
        }
        if (isSentinel(latitude, longitude)) {
            return;
        }
        Double altitude = number(fields.get("alt"));
        positions.add(new Position(index, time.longValue(), latitude, longitude, altitude));
    }

    private static void addSample(Map<String, String> fields, List<Sample> samples) {
        Double time = number(fields.get("k"));
        Double value = number(fields.get("v"));
        if (time == null || value == null || value <= 0) {
            return;
        }
        samples.add(new Sample(time.longValue(), value));
    }

    private static void addDistance(Map<String, String> fields, Map<Integer, Double> distances) {
        Integer index = integer(fields.get("k"));
        Double value = number(fields.get("v"));
        if (index != null && value != null) {
            distances.put(index, value);
        }
    }

    /** (90, -80) 是华为在地铁、隧道里丢失定位时写入的占位点。 */
    private static boolean isSentinel(double latitude, double longitude) {
        return Math.abs(latitude - SENTINEL_LATITUDE) < 1e-6
                && Math.abs(longitude - SENTINEL_LONGITUDE) < 1e-6;
    }

    /** 拆 {@code tp=lbs;k=0;lat=..} 这样的键值串，末尾分号可有可无。 */
    static Map<String, String> fields(String line) {
        Map<String, String> fields = new HashMap<>(8);
        String[] parts = line.split(";");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int separator = part.indexOf('=');
            if (separator <= 0 || separator == part.length() - 1) {
                continue;
            }
            fields.put(part.substring(0, separator).strip(), part.substring(separator + 1).strip());
        }
        return fields;
    }

    static Double number(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer integer(String text) {
        Double value = number(text);
        return value == null ? null : (int) Math.round(value);
    }

    private static Double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    private static Integer rounded(Double value) {
        return value == null ? null : (int) Math.round(value);
    }
}
