package org.example.sporthealth.support;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.example.sporthealth.domain.TrackPoint;

/**
 * 轨迹的几何索引：把一条 {@link TrackPoint} 列表换算成“累计距离 ↔ 时间 ↔ 海拔”的曲线。
 *
 * <p>分段配速、最佳努力、海拔剖面都依赖同一件事：给一个累计距离，能插值出对应时间。
 * 这里把计算集中一次，避免每个功能各写一遍 haversine 和插值。
 *
 * <p>坐标口径：
 * <ul>
 *   <li>只有 {@link TrackPoint#hasPosition()} 为真且时间戳存在的点才推进累计距离；</li>
 *   <li>缺坐标的点仍保留时间与海拔，距离沿用上一个有效点（不影响时间轴）；</li>
 *   <li>累计距离按 haversine 逐点累加，不做平滑，因此与 Lap 汇总值可能有少量差异。</li>
 * </ul>
 *
 * <p>插值假设两个采样点之间匀速直线运动，这是 GPS 轨迹的常规近似。
 */
public final class TrackPath {

    private static final double EARTH_RADIUS_METERS = 6_371_008.8;
    private static final double EPSILON = 1e-6;

    /**
     * 轨迹上的一个采样点。
     *
     * @param distanceMeters 距起点的累计距离（米）
     * @param seconds        距首个采样点的时间（秒）
     * @param latitude       纬度，缺坐标时为 null
     * @param longitude      经度，缺坐标时为 null
     * @param altitudeMeters 海拔（米），缺失时为 null
     * @param cadence        该时刻步频（步/分），缺失时为 null（TCX 来源没有这个字段）
     */
    public record Node(double distanceMeters, double seconds,
                       Double latitude, Double longitude, Double altitudeMeters, Integer cadence) {
    }

    private final List<Node> nodes;
    private final List<Node> distanceNodes;
    private final double totalDistanceMeters;
    private final double totalSeconds;

    private TrackPath(List<Node> nodes, List<Node> distanceNodes) {
        this.nodes = List.copyOf(nodes);
        this.distanceNodes = List.copyOf(distanceNodes);
        this.totalDistanceMeters = distanceNodes.isEmpty()
                ? 0d
                : distanceNodes.get(distanceNodes.size() - 1).distanceMeters();
        this.totalSeconds = distanceNodes.isEmpty()
                ? 0d
                : distanceNodes.get(distanceNodes.size() - 1).seconds()
                        - distanceNodes.get(0).seconds();
    }

    /**
     * 构建索引。空列表、单点、时间戳乱序都能安全处理，只是 {@link #usable()} 为 false。
     */
    public static TrackPath of(List<TrackPoint> points) {
        List<Node> nodes = new ArrayList<>();
        List<Node> distanceNodes = new ArrayList<>();
        if (points == null || points.isEmpty()) {
            return new TrackPath(nodes, distanceNodes);
        }

        LocalDateTime origin = null;
        double distanceMeters = 0d;
        Node previousPosition = null;

        for (TrackPoint point : points) {
            if (point == null || point.timestamp() == null) {
                continue;
            }
            if (origin == null) {
                origin = point.timestamp();
            }
            double seconds = Math.max(0d, Duration.between(origin, point.timestamp()).toMillis() / 1000d);

            if (!point.hasPosition()) {
                nodes.add(new Node(distanceMeters, seconds, null, null,
                        point.altitudeMeters(), point.cadence()));
                continue;
            }

            if (previousPosition != null) {
                distanceMeters += haversineMeters(
                        previousPosition.latitude(), previousPosition.longitude(),
                        point.latitude(), point.longitude());
            }
            Node node = new Node(distanceMeters, seconds,
                    point.latitude(), point.longitude(), point.altitudeMeters(), point.cadence());
            nodes.add(node);

            // 同一坐标重复出现时距离不增长，插值轴只保留最后一个点（时间/海拔更新）。
            if (distanceNodes.isEmpty()
                    || distanceMeters > distanceNodes.get(distanceNodes.size() - 1).distanceMeters() + EPSILON) {
                distanceNodes.add(node);
            } else {
                distanceNodes.set(distanceNodes.size() - 1, node);
            }
            previousPosition = node;
        }
        return new TrackPath(nodes, distanceNodes);
    }

    /** 是否至少有两个有效坐标点且累计距离大于 0。 */
    public boolean usable() {
        return distanceNodes.size() >= 2 && totalDistanceMeters > EPSILON;
    }

    /** 轨迹累计距离（米）。 */
    public double totalDistanceMeters() {
        return totalDistanceMeters;
    }

    /** 首末有效坐标点之间的时间差（秒）。 */
    public double totalSeconds() {
        return totalSeconds;
    }

    /** 所有点（含缺坐标的点），按原始顺序，累计距离单调不减。 */
    public List<Node> nodes() {
        return nodes;
    }

    /** 只含有效坐标、累计距离严格递增的点，供距离插值使用。 */
    public List<Node> distanceNodes() {
        return distanceNodes;
    }

    /**
     * 累计距离对应的时间（秒，相对首个采样点）。越界自动夹取到首尾。
     */
    public double timeAtDistance(double distanceMeters) {
        if (distanceNodes.isEmpty()) {
            return 0d;
        }
        if (distanceMeters <= 0d) {
            return distanceNodes.get(0).seconds();
        }
        if (distanceMeters >= totalDistanceMeters) {
            return distanceNodes.get(distanceNodes.size() - 1).seconds();
        }
        int index = floorIndex(distanceMeters);
        Node from = distanceNodes.get(index);
        Node to = distanceNodes.get(index + 1);
        return interpolate(from.seconds(), to.seconds(),
                (distanceMeters - from.distanceMeters()) / (to.distanceMeters() - from.distanceMeters()));
    }

    /**
     * 时间（秒）对应的累计距离（米）。{@link #timeAtDistance} 的反函数，越界自动夹取到首尾。
     *
     * <p>跑姿流（{@code rp}）的 {@code k} 是相对起跑的秒数而不是轨迹点序号，要靠这个把每个
     * 采样点落到距离轴上，才能按公里切开。缺坐标的点距离不增长，插值时按秒数比例取前后两点。
     */
    public double distanceAtTime(double seconds) {
        if (nodes.isEmpty()) {
            return 0d;
        }
        if (seconds <= nodes.get(0).seconds()) {
            return nodes.get(0).distanceMeters();
        }
        Node last = nodes.get(nodes.size() - 1);
        if (seconds >= last.seconds()) {
            return last.distanceMeters();
        }
        int index = lastIndexAtOrBefore(seconds);
        Node from = nodes.get(index);
        Node to = nodes.get(index + 1);
        double span = to.seconds() - from.seconds();
        if (span <= EPSILON) {
            return to.distanceMeters();
        }
        return interpolate(from.distanceMeters(), to.distanceMeters(),
                (seconds - from.seconds()) / span);
    }

    /**
     * {@code [fromMeters, toMeters]} 区间内的平均步频（步/分），按每个采样点覆盖的时长加权；
     * 没有步频数据时返回 {@code null}。
     *
     * <p>用时间加权而不是算术平均：采样间隔不均匀时（GPS 丢点、停顿），算术平均会让
     * 密集段（通常是停顿附近）的权重虚高。
     */
    public Integer averageCadenceBetween(double fromMeters, double toMeters) {
        double start = Math.clamp(fromMeters, 0d, totalDistanceMeters);
        double end = Math.clamp(toMeters, start, totalDistanceMeters);
        double weighted = 0d;
        double weightSeconds = 0d;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node.cadence() == null || node.cadence() <= 0) {
                continue;
            }
            if (node.distanceMeters() < start - EPSILON || node.distanceMeters() > end + EPSILON) {
                continue;
            }
            double weight = 1d;
            if (i + 1 < nodes.size()) {
                double next = nodes.get(i + 1).seconds() - node.seconds();
                if (next > EPSILON) {
                    weight = next;
                }
            }
            weighted += node.cadence() * weight;
            weightSeconds += weight;
        }
        return weightSeconds <= 0d ? null : (int) Math.round(weighted / weightSeconds);
    }

    /**
     * 累计距离对应的海拔（米）。两侧都有值时线性插值，只有一侧时取该侧，完全没有时返回 null。
     */
    public Double altitudeAtDistance(double distanceMeters) {
        if (distanceNodes.isEmpty()) {
            return null;
        }
        if (distanceMeters <= 0d) {
            return distanceNodes.get(0).altitudeMeters();
        }
        if (distanceMeters >= totalDistanceMeters) {
            return distanceNodes.get(distanceNodes.size() - 1).altitudeMeters();
        }
        int index = floorIndex(distanceMeters);
        Node from = distanceNodes.get(index);
        Node to = distanceNodes.get(index + 1);
        Double start = from.altitudeMeters();
        Double end = to.altitudeMeters();
        if (start == null || end == null) {
            return start != null ? start : end;
        }
        return interpolate(start, end,
                (distanceMeters - from.distanceMeters()) / (to.distanceMeters() - from.distanceMeters()));
    }

    /**
     * 取 [from, to] 区间内的海拔序列，包含两端插值。跳过缺失海拔的点。
     * 顺序按轨迹前进方向，供坡度与爬升计算使用。
     */
    public List<Double> altitudesBetween(double fromMeters, double toMeters) {
        double start = Math.clamp(fromMeters, 0d, totalDistanceMeters);
        double end = Math.clamp(toMeters, start, totalDistanceMeters);
        List<Double> altitudes = new ArrayList<>();
        Double startAltitude = altitudeAtDistance(start);
        if (startAltitude != null) {
            altitudes.add(startAltitude);
        }
        for (Node node : nodes) {
            if (node.distanceMeters() > start + EPSILON
                    && node.distanceMeters() < end - EPSILON
                    && node.altitudeMeters() != null) {
                altitudes.add(node.altitudeMeters());
            }
        }
        Double endAltitude = altitudeAtDistance(end);
        if (endAltitude != null) {
            altitudes.add(endAltitude);
        }
        return altitudes;
    }

    /** 最后一个 {@code seconds} 不超过给定值的点；时间轴单调不减，可直接二分。 */
    private int lastIndexAtOrBefore(double seconds) {
        int low = 0;
        int high = nodes.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (nodes.get(mid).seconds() <= seconds) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return Math.min(low, nodes.size() - 2);
    }

    private int floorIndex(double distanceMeters) {        int low = 0;
        int high = distanceNodes.size() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (distanceNodes.get(mid).distanceMeters() <= distanceMeters) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return Math.min(low, distanceNodes.size() - 2);
    }

    /** 两个经纬度点之间的球面距离（米）。 */
    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    private static double interpolate(double from, double to, double ratio) {
        return from + (to - from) * ratio;
    }
}
