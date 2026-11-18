package org.example.sporthealth.importer.huawei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 坐标换算的核心断言：偏移量在城市尺度上必须是几百米级，且往返可逆。
 *
 * <p>用真实样本点（10-25 那次跑步的第一个轨迹点）而不是编造坐标：
 * 如果公式写错，偏移会变成一个固定的荒唐值，用真实点才能一眼看出来。
 */
class Gcj02Test {

    /** 华为 JSON 导出里的原始点（GCJ-02）。 */
    private static final double GCJ_LATITUDE = 39.905708;
    private static final double GCJ_LONGITUDE = 116.236651;

    @Test
    void shiftsBeijingCoordinateByHundredsOfMeters() {
        double[] wgs = Gcj02.toWgs84(GCJ_LATITUDE, GCJ_LONGITUDE);

        double shift = distanceMeters(GCJ_LATITUDE, GCJ_LONGITUDE, wgs[0], wgs[1]);
        assertTrue(shift > 300 && shift < 800, "偏移应在数百米量级，实际 " + shift + " 米");
        assertTrue(wgs[0] < GCJ_LATITUDE, "北京地区的纬度应向南修正");
        assertTrue(wgs[1] < GCJ_LONGITUDE, "北京地区的经度应向西修正");
    }

    @Test
    void roundTripReturnsOriginalPoint() {
        double[] wgs = Gcj02.toWgs84(GCJ_LATITUDE, GCJ_LONGITUDE);
        double[] back = Gcj02.toGcj02(wgs[0], wgs[1]);

        assertEquals(GCJ_LATITUDE, back[0], 1e-6);
        assertEquals(GCJ_LONGITUDE, back[1], 1e-6);
    }

    @Test
    void leavesOverseasCoordinatesUntouched() {
        double[] wgs = Gcj02.toWgs84(48.8584, 2.2945);

        assertEquals(48.8584, wgs[0], 1e-9);
        assertEquals(2.2945, wgs[1], 1e-9);
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * earthRadius * Math.asin(Math.sqrt(a));
    }
}
