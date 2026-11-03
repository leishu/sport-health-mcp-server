package org.example.sporthealth.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.example.sporthealth.config.HealthDataProperties;
import org.example.sporthealth.domain.RunActivity;
import org.example.sporthealth.domain.TrackPoint;
import org.example.sporthealth.support.RouteTypes;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 解析华为运动健康导出的 TCX（Training Center Database v2）。
 *
 * <p>真实导出结构（华为的极简子集，不含 Calories/Intensity/心率等字段）：
 * <pre>
 * TrainingCenterDatabase[@creator]/Activities/Activity[@Sport]
 *   Id
 *   Lap[@StartTime]
 *     TotalTimeSeconds, DistanceMeters, CumulativeClimb, CumulativeDecrease
 *     Extensions/RouteType
 *     Track/Trackpoint{Time, Position{LatitudeDegrees, LongitudeDegrees}, AltitudeMeters}
 * </pre>
 *
 * <p>解析规则：
 * <ol>
 *   <li>按元素 {@code localName} 匹配，兼容不同命名空间前缀；</li>
 *   <li>时间均为 UTC，统一换算到配置时区后再取日期；</li>
 *   <li>一个 Lap 下的多个 Track 会全部读取；多个 Lap 的时长、距离、爬升、轨迹累加；</li>
 *   <li>开始时间优先级：{@code 首个 Lap@StartTime -> Activity/Id -> 首个轨迹点}；</li>
 *   <li>时长/距离在“全部 Lap 都有汇总字段”时才求和，否则回退到轨迹首末时间差或
 *       haversine 累积，避免把部分和当成总量；</li>
 *   <li>字段级问题不抛异常，而是收集到 {@link ParsedRun#warnings()}：
 *       缺 {@code Time}、时间/数字无法解析、缺经纬度、(0,0) 坐标、缺海拔、部分 Lap 缺汇总字段。</li>
 * </ol>
 *
 * <p>线程安全：除不可变的 {@link ZoneId} 外无状态，可并发调用。
 */
@Component
public class TcxParser {

    private static final double EARTH_RADIUS_METERS = 6_371_000d;

    private final ZoneId zone;

    public TcxParser(HealthDataProperties properties) {
        this.zone = properties.zone();
    }

    /** 解析本地文件；读取失败抛 {@link TcxDataException}，内容问题见返回值的 warnings。 */
    public List<ParsedRun> parse(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return parse(file.getFileName().toString(), input);
        } catch (IOException e) {
            throw new TcxDataException("读取 TCX 文件失败: " + file.getFileName() + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 解析一个 TCX 输入流。
     *
     * @param fileName 仅用于生成记录 id、错误信息与 {@code sourceFile}，不参与 IO
     * @param input    由调用方负责关闭
     * @return 每个 {@code Activity} 一条结果，保持文档顺序
     * @throws TcxDataException 文件无法解析、没有 Activity/Lap、或完全无法确定开始时间
     */
    public List<ParsedRun> parse(String fileName, InputStream input) {
        Document document = readDocument(fileName, input);
        Element root = document.getDocumentElement();
        String creator = attribute(root, "creator");
        Element activities = child(root, "Activities");
        List<Element> activityElements = activities == null ? List.of() : children(activities, "Activity");
        if (activityElements.isEmpty()) {
            throw new TcxDataException("TCX 文件 " + fileName + " 中没有 Activity 节点");
        }
        String baseName = fileBaseName(fileName);
        List<ParsedRun> runs = new ArrayList<>(activityElements.size());
        for (int i = 0; i < activityElements.size(); i++) {
            String id = activityElements.size() == 1 ? baseName : baseName + "#" + (i + 1);
            runs.add(toParsedRun(id, fileName, activityElements.get(i), creator));
        }
        return runs;
    }

    /** 把一个 {@code Activity} 解析为摘要、轨迹与字段级告警。 */
    private ParsedRun toParsedRun(String id, String fileName, Element activity, String creator) {
        List<String> warnings = new ArrayList<>();
        String sport = attribute(activity, "Sport");
        String activityId = text(activity, "Id");
        List<Element> laps = children(activity, "Lap");
        if (laps.isEmpty()) {
            throw new TcxDataException("文件 " + fileName + " 的 Activity 没有 Lap 节点");
        }

        Instant firstLapStart = null;
        double lapDurationSum = 0d;
        boolean allLapsHaveDuration = true;
        double lapDistanceSum = 0d;
        boolean allLapsHaveDistance = true;
        Double climb = null;
        Double descent = null;
        String routeType = null;
        List<TrackPoint> points = new ArrayList<>();
        TrackCounters counters = new TrackCounters();

        for (int lapIndex = 0; lapIndex < laps.size(); lapIndex++) {
            Element lap = laps.get(lapIndex);
            // 只用第一个 Lap 的 StartTime，后续 Lap 的时间不能覆盖活动开始时间
            if (lapIndex == 0) {
                String lapStart = attribute(lap, "StartTime");
                if (lapStart != null) {
                    firstLapStart = parseInstantQuietly(lapStart, "Lap@StartTime", warnings);
                }
            }

            Double totalTime = number(lap, "TotalTimeSeconds", warnings, "Lap");
            Double distance = number(lap, "DistanceMeters", warnings, "Lap");
            Double lapClimb = number(lap, "CumulativeClimb", warnings, "Lap");
            Double lapDescent = number(lap, "CumulativeDecrease", warnings, "Lap");

            if (totalTime == null) {
                allLapsHaveDuration = false;
            } else {
                lapDurationSum += totalTime;
            }
            if (distance == null) {
                allLapsHaveDistance = false;
            } else {
                lapDistanceSum += distance;
            }

            climb = add(climb, lapClimb);
            descent = add(descent, lapDescent);

            if (routeType == null) {
                Element extensions = child(lap, "Extensions");
                if (extensions != null) {
                    routeType = text(extensions, "RouteType");
                }
            }

            // TCX 允许一个 Lap 下有多个 Track（例如中途暂停后继续），必须全部读取
            for (Element track : children(lap, "Track")) {
                parseTrackpoints(track, points, counters);
            }
        }
        counters.flush(warnings);

        LocalDateTime firstPointTime = points.isEmpty() ? null : points.get(0).timestamp();
        LocalDateTime lastPointTime = points.isEmpty() ? null : points.get(points.size() - 1).timestamp();

        double durationSeconds = resolveDuration(lapDurationSum, allLapsHaveDuration,
                firstPointTime, lastPointTime, warnings);
        double distanceMeters = resolveDistance(lapDistanceSum, allLapsHaveDistance, points, warnings);

        // 开始时间优先级：Lap@StartTime -> Activity/Id -> 首个轨迹点
        Instant activityIdStart = parseInstantQuietly(activityId, "Activity/Id", warnings);
        Instant startUtc = firstLapStart != null ? firstLapStart : activityIdStart;
        LocalDateTime startTime = startUtc != null
                ? LocalDateTime.ofInstant(startUtc, zone)
                : firstPointTime;
        if (startTime == null) {
            throw new TcxDataException("文件 " + fileName + " 无法确定开始时间（Lap@StartTime、Activity/Id 与 Trackpoint 均缺失）");
        }
        if (startUtc == null) {
            startUtc = startTime.atZone(zone).toInstant();
        }
        LocalDateTime endTime = startTime.plusSeconds(Math.round(durationSeconds));

        TrackPoint first = firstValidPoint(points, true);
        TrackPoint last = firstValidPoint(points, false);

        RunActivity run = new RunActivity(
                id,
                activityId,
                sport,
                startTime.toLocalDate(),
                startTime,
                endTime,
                startUtc,
                round(durationSeconds, 1),
                round(distanceMeters, 1),
                roundOrNull(climb, 1),
                roundOrNull(descent, 1),
                routeType,
                RouteTypes.text(routeType),
                points.size(),
                speedKmh(distanceMeters, durationSeconds),
                paceSecondsPerKm(distanceMeters, durationSeconds),
                first == null ? null : first.latitude(),
                first == null ? null : first.longitude(),
                last == null ? null : last.latitude(),
                last == null ? null : last.longitude(),
                roundOrNull(minAltitude(points), 1),
                roundOrNull(maxAltitude(points), 1),
                fileName,
                creator);
        return new ParsedRun(run, List.copyOf(points), List.copyOf(warnings));
    }

    /**
     * 总时长：所有 Lap 都有 TotalTimeSeconds 时直接求和；否则优先用轨迹首末时间差，
     * 避免“部分 Lap 缺失”时把部分和当成总时长。
     */
    private double resolveDuration(double lapDurationSum, boolean allLapsHaveDuration,
                                   LocalDateTime firstPointTime, LocalDateTime lastPointTime,
                                   List<String> warnings) {
        if (allLapsHaveDuration && lapDurationSum > 0) {
            return lapDurationSum;
        }
        double derived = secondsBetween(firstPointTime, lastPointTime);
        if (derived > 0) {
            if (!allLapsHaveDuration) {
                warnings.add("部分 Lap 缺少 TotalTimeSeconds，总时长改用轨迹首末时间差");
            }
            return derived;
        }
        if (!allLapsHaveDuration) {
            warnings.add("部分 Lap 缺少 TotalTimeSeconds 且轨迹点不足，总时长可能偏小");
        }
        return lapDurationSum;
    }

    /** 总距离：处理方式同 {@link #resolveDuration}，缺失时改用 haversine 累积。 */
    private double resolveDistance(double lapDistanceSum, boolean allLapsHaveDistance,
                                   List<TrackPoint> points, List<String> warnings) {
        if (allLapsHaveDistance && lapDistanceSum > 0) {
            return lapDistanceSum;
        }
        double derived = pathLength(points);
        if (derived > 0) {
            if (!allLapsHaveDistance) {
                warnings.add("部分 Lap 缺少 DistanceMeters，总距离改用轨迹累积");
            }
            return derived;
        }
        if (!allLapsHaveDistance) {
            warnings.add("部分 Lap 缺少 DistanceMeters 且轨迹点不足，总距离可能偏小");
        }
        return lapDistanceSum;
    }

    /**
     * 解析单个 Track。缺 Time 或时间无法解析的点会被跳过并计数；
     * 缺经纬度的点仍保留（时间和海拔有用），坐标置为 null。
     */
    private void parseTrackpoints(Element track, List<TrackPoint> out, TrackCounters counters) {
        for (Element trackpoint : children(track, "Trackpoint")) {
            String timeText = text(trackpoint, "Time");
            if (timeText == null) {
                counters.skippedNoTime++;
                continue;
            }
            LocalDateTime time;
            try {
                time = LocalDateTime.ofInstant(parseInstant(timeText), zone);
            } catch (TcxDataException e) {
                counters.skippedBadTime++;
                continue;
            }

            Element position = child(trackpoint, "Position");
            Double latitude = null;
            Double longitude = null;
            if (position == null) {
                counters.missingPosition++;
            } else {
                String latText = text(position, "LatitudeDegrees");
                String lonText = text(position, "LongitudeDegrees");
                latitude = parseNumberQuiet(latText, counters);
                longitude = parseNumberQuiet(lonText, counters);
                if (latitude == null || longitude == null) {
                    counters.missingPosition++;
                    latitude = null;
                    longitude = null;
                } else if (latitude == 0d && longitude == 0d) {
                    counters.zeroPosition++;
                }
            }

            String altitudeText = text(trackpoint, "AltitudeMeters");
            Double altitude = null;
            if (altitudeText != null) {
                altitude = parseNumberQuiet(altitudeText, counters);
                if (altitude == null) {
                    counters.missingAltitude++;
                }
            } else {
                counters.missingAltitude++;
            }

            out.add(new TrackPoint(time, latitude, longitude, altitude));
        }
    }

    private Document readDocument(String fileName, InputStream input) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
            setAttributeQuietly(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(input);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new TcxDataException("解析 TCX 文件失败: " + fileName + " -> " + e.getMessage(), e);
        }
    }

    private static void setFeatureQuietly(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException ignored) {
            // 部分实现不支持该特性，安全兜底由组合开关保证
        }
    }

    private static void setAttributeQuietly(DocumentBuilderFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
            // 同上
        }
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(nodeName(node))) {
                result.add((Element) node);
            }
        }
        return result;
    }

    private static Element child(Element parent, String localName) {
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(nodeName(node))) {
                return (Element) node;
            }
        }
        return null;
    }

    private static String nodeName(Node node) {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static String text(Element parent, String localName) {
        Element element = child(parent, localName);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // 继续尝试其它格式
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // 继续尝试无偏移格式
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new TcxDataException("无法解析 TCX 时间: " + value);
        }
    }

    private Instant parseInstantQuietly(String value, String field, List<String> warnings) {
        if (value == null) {
            return null;
        }
        try {
            return parseInstant(value);
        } catch (TcxDataException e) {
            warnings.add(field + " 无法解析，已忽略: " + value);
            return null;
        }
    }

    /** 读取并校验数字字段，缺失返回 null，非法值记入 warnings。 */
    private Double number(Element parent, String field, List<String> warnings, String scope) {
        String raw = text(parent, field);
        if (raw == null) {
            return null;
        }
        Double value = parseNumber(raw);
        if (value == null) {
            warnings.add(scope + " 的 " + field + " 不是合法数字，已忽略: " + raw);
        }
        return value;
    }

    private static Double parseNumberQuiet(String raw, TrackCounters counters) {
        if (raw == null) {
            return null;
        }
        Double value = parseNumber(raw);
        if (value == null) {
            counters.invalidNumber++;
        }
        return value;
    }

    private static Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double add(Double left, Double right) {
        if (right == null) {
            return left;
        }
        return left == null ? right : left + right;
    }

    private static double secondsBetween(LocalDateTime from, LocalDateTime to) {
        return (from == null || to == null) ? 0d : Duration.between(from, to).toSeconds();
    }

    private static double pathLength(List<TrackPoint> points) {
        double total = 0d;
        TrackPoint previous = null;
        for (TrackPoint point : points) {
            if (isInvalid(point)) {
                continue;
            }
            if (previous != null) {
                total += haversine(previous, point);
            }
            previous = point;
        }
        return total;
    }

    private static double haversine(TrackPoint from, TrackPoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    private static boolean isInvalid(TrackPoint point) {
        return point == null || !point.hasPosition();
    }

    private static TrackPoint firstValidPoint(List<TrackPoint> points, boolean fromStart) {
        if (fromStart) {
            for (TrackPoint point : points) {
                if (!isInvalid(point)) {
                    return point;
                }
            }
        } else {
            for (int i = points.size() - 1; i >= 0; i--) {
                if (!isInvalid(points.get(i))) {
                    return points.get(i);
                }
            }
        }
        return null;
    }

    private static Double minAltitude(List<TrackPoint> points) {
        Double min = null;
        for (TrackPoint point : points) {
            if (point.altitudeMeters() != null && (min == null || point.altitudeMeters() < min)) {
                min = point.altitudeMeters();
            }
        }
        return min;
    }

    private static Double maxAltitude(List<TrackPoint> points) {
        Double max = null;
        for (TrackPoint point : points) {
            if (point.altitudeMeters() != null && (max == null || point.altitudeMeters() > max)) {
                max = point.altitudeMeters();
            }
        }
        return max;
    }

    private static Double paceSecondsPerKm(double distanceMeters, double durationSeconds) {
        if (distanceMeters <= 0 || durationSeconds <= 0) {
            return null;
        }
        return round(durationSeconds / (distanceMeters / 1000d), 1);
    }

    private static Double speedKmh(double distanceMeters, double durationSeconds) {
        if (distanceMeters <= 0 || durationSeconds <= 0) {
            return null;
        }
        return round((distanceMeters / 1000d) / (durationSeconds / 3600d), 2);
    }

    private static Double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }

    /** TCX 里的 CumulativeClimb 常带浮点噪声（如 15.800000190734863），统一保留一位小数。 */
    private static Double roundOrNull(Double value, int scale) {
        return value == null ? null : round(value, scale);
    }

    private static String fileBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 轨迹点字段级问题的计数器。逐点记 warning 会刷屏，这里按类型汇总成一条。
     */
    private static final class TrackCounters {
        private int skippedNoTime;
        private int skippedBadTime;
        private int missingPosition;
        private int zeroPosition;
        private int missingAltitude;
        private int invalidNumber;

        private void flush(List<String> warnings) {
            if (skippedNoTime > 0) {
                warnings.add(skippedNoTime + " 个 Trackpoint 缺少 Time，已跳过");
            }
            if (skippedBadTime > 0) {
                warnings.add(skippedBadTime + " 个 Trackpoint 的 Time 无法解析，已跳过");
            }
            if (missingPosition > 0) {
                warnings.add(missingPosition + " 个 Trackpoint 缺少经纬度，已保留时间与海拔");
            }
            if (zeroPosition > 0) {
                warnings.add(zeroPosition + " 个 Trackpoint 坐标为 (0,0)，按无效坐标处理");
            }
            if (missingAltitude > 0) {
                warnings.add(missingAltitude + " 个 Trackpoint 缺少 AltitudeMeters");
            }
            if (invalidNumber > 0) {
                warnings.add(invalidNumber + " 个 Trackpoint 的数字字段不是合法值，已置为 null");
            }
        }
    }
}
