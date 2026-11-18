package org.example.sporthealth.importer.huawei;

/**
 * GCJ-02 与 WGS-84 之间的坐标换算。
 *
 * <p>华为 JSON 导出的轨迹 {@code summaryData.coordinate} 明确标注为 {@code GCJ02}，
 * 而 TCX 导出与 open-meteo 天气接口用的都是 WGS-84。同一次跑步（10-25，两边都是 7123 米、
 * 同一秒起跑）在两种格式里的起点相差约 540 米——如果直接把 JSON 坐标写进
 * {@code track_point}，历史 TCX 轨迹和天气回填的坐标就会对不上，地图上同一条路线会错开半条街。
 *
 * <p>因此导入时统一转成 WGS-84 落库，{@code run_activity} 与 {@code track_point} 里只保留一套坐标系。
 *
 * <p>算法是公开的「火星坐标」偏移公式：先正向加密一次，再迭代三次修正残差。
 * 迭代法在城市尺度上的误差小于 1 米，足够轨迹分析使用。
 */
public final class Gcj02 {

    /** 克拉索夫斯基椭球长半轴。 */
    private static final double SEMI_MAJOR_AXIS = 6378245.0;

    /** 椭球第一偏心率平方。 */
    private static final double ECCENTRICITY_SQUARED = 0.00669342162296594323;

    /** 迭代次数：三次已收敛到厘米级。 */
    private static final int ITERATIONS = 3;

    private Gcj02() {
    }

    /**
     * 把 GCJ-02 坐标转成 WGS-84。
     *
     * <p>中国境外的坐标不做偏移（返回原值）；纬度 90、经度 -80 这类华为用来表示
     * 「此刻没有 GPS 定位」的哨兵值也原样返回，由调用方按无效点丢弃。
     *
     * @return 长度为 2 的数组：{@code [纬度, 经度]}
     */
    public static double[] toWgs84(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new double[] {latitude, longitude};
        }
        double wgsLatitude = latitude;
        double wgsLongitude = longitude;
        for (int i = 0; i < ITERATIONS; i++) {
            double[] encrypted = toGcj02(wgsLatitude, wgsLongitude);
            wgsLatitude -= encrypted[0] - latitude;
            wgsLongitude -= encrypted[1] - longitude;
        }
        return new double[] {wgsLatitude, wgsLongitude};
    }

    /** WGS-84 加密成 GCJ-02，{@link #toWgs84} 的反向迭代要用它。 */
    static double[] toGcj02(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) {
            return new double[] {latitude, longitude};
        }
        double deltaLatitude = transformLatitude(longitude - 105.0, latitude - 35.0);
        double deltaLongitude = transformLongitude(longitude - 105.0, latitude - 35.0);
        double radianLatitude = latitude / 180.0 * Math.PI;
        double magic = Math.sin(radianLatitude);
        magic = 1 - ECCENTRICITY_SQUARED * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        deltaLatitude = (deltaLatitude * 180.0)
                / ((SEMI_MAJOR_AXIS * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic) * Math.PI);
        deltaLongitude = (deltaLongitude * 180.0)
                / (SEMI_MAJOR_AXIS / sqrtMagic * Math.cos(radianLatitude) * Math.PI);
        return new double[] {latitude + deltaLatitude, longitude + deltaLongitude};
    }

    /** 粗略判断是否在中国境外；境外不做偏移。 */
    static boolean outOfChina(double latitude, double longitude) {
        return longitude < 72.004 || longitude > 137.8347 || latitude < 0.8293 || latitude > 55.8271;
    }

    private static double transformLatitude(double x, double y) {
        double result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return result;
    }

    private static double transformLongitude(double x, double y) {
        double result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        result += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        result += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        result += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return result;
    }
}
