package org.example.sporthealth.importer.huawei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.util.List;

import org.example.sporthealth.domain.TrackPoint;
import org.junit.jupiter.api.Test;

/**
 * {@code detailData} 自定义协议的解析语义。
 *
 * <p>重点覆盖三件事：丢定位哨兵要被丢弃、心率/步频要按时间就近挂到轨迹点上、
 * 累计距离按点序号对齐。
 */
class MotionTrackDataTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一分钟内三个 GPS 点，中间夹着心率、步频、海拔、累计距离。 */
    private static final String DETAIL = """
            tp=lbs;k=0;lat=39.905708;lon=116.236651;alt=54.5;t=1769902015000;
            tp=h-r;k=1769902014000;v=128;
            tp=s-r;k=1769902010000;v=180;
            tp=alt;k=1769902014000;v=58.8;
            tp=dis;k=0;v=0.0;
            tp=lbs;k=1;lat=39.905808;lon=116.236751;alt=55.5;t=1769902025000;
            tp=h-r;k=1769902025000;v=132;
            tp=dis;k=1;v=12.5;
            tp=lbs;k=2;lat=90.0;lon=-80.0;alt=0.0;t=1769902035000;
            tp=rs;k=5;v=0.0;
            """;

    @Test
    void dropsSentinelPointsAndKeepsRealOnes() {
        List<TrackPoint> points = MotionTrackData.parse(DETAIL).toTrackPoints(ZONE);

        assertEquals(2, points.size(), "纬度 90 / 经度 -80 是「无定位」占位点，必须丢弃");
        assertTrue(points.stream().allMatch(TrackPoint::hasPosition));
    }

    @Test
    void convertsCoordinateToWgs84() {
        TrackPoint first = MotionTrackData.parse(DETAIL).toTrackPoints(ZONE).get(0);

        assertTrue(first.latitude() < 39.905708, "GCJ-02 转 WGS-84 后纬度应变小");
        assertTrue(first.longitude() < 116.236651, "GCJ-02 转 WGS-84 后经度应变小");
    }

    @Test
    void attachesNearestHeartRateAndCadence() {
        List<TrackPoint> points = MotionTrackData.parse(DETAIL).toTrackPoints(ZONE);

        assertEquals(128, points.get(0).heartRate());
        assertEquals(180, points.get(0).cadence());
        assertEquals(132, points.get(1).heartRate(), "第二个点应拿到下一拍的 132");
        assertEquals(180, points.get(1).cadence(), "步频采样稀疏，120 秒容差内复用上一次的值");
    }

    @Test
    void prefersBarometerAltitudeOverGpsAltitude() {
        List<TrackPoint> points = MotionTrackData.parse(DETAIL).toTrackPoints(ZONE);

        assertEquals(58.8, points.get(0).altitudeMeters(), 0.001);
    }

    @Test
    void alignsCumulativeDistanceByPointIndex() {
        List<TrackPoint> points = MotionTrackData.parse(DETAIL).toTrackPoints(ZONE);

        assertEquals(0.0, points.get(0).distanceMeters(), 0.001);
        assertEquals(12.5, points.get(1).distanceMeters(), 0.001);
    }

    @Test
    void toleratesGarbageAndEmptyInput() {
        assertTrue(MotionTrackData.parse(null).toTrackPoints(ZONE).isEmpty());
        assertTrue(MotionTrackData.parse("lbs broken line\n\ntp=lbs;k=0;lat=notanumber;lon=1;t=1;")
                .toTrackPoints(ZONE).isEmpty());
    }

    @Test
    void parsesKeyValueLine() {
        var fields = MotionTrackData.fields("tp=lbs;k=7;lat=1.5;lon=2.5;");

        assertEquals("lbs", fields.get("tp"));
        assertEquals("7", fields.get("k"));
        assertEquals("2.5", fields.get("lon"));
    }
}
