package org.example.sporthealth.weather;

/**
 * 风向角度到 8 方位中文的映射。
 *
 * <p>角度按气象学约定记录「风的来向」：{@code 315°} 是西北风，也就是风从西北吹来。
 * 对讲「今天吹什么风」而言 1° 精度没有意义，8 个方位够用；每个方位覆盖 45°，
 * 用 {@code Math.round(degrees / 45)} 分桶，边界值归入上一个方位。
 *
 * <p>不做成枚举：这只是一层展示用的文本，角度本身才是数据。
 */
public final class WindDirections {

    private static final String[] DIRECTIONS = {"北", "东北", "东", "东南", "南", "西南", "西", "西北"};

    private WindDirections() {
    }

    /**
     * @param degrees 风的来向（°），可为 null
     * @return 8 方位中文；{@code null} 返回 null
     */
    public static String text(Double degrees) {
        if (degrees == null) {
            return null;
        }
        double normalized = ((degrees % 360d) + 360d) % 360d;
        return DIRECTIONS[(int) Math.round(normalized / 45d) % DIRECTIONS.length];
    }
}
