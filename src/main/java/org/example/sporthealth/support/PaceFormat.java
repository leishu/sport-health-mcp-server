package org.example.sporthealth.support;

/**
 * 配速、速度、时长的易读格式，便于大模型直接复述给用户。
 *
 * <p>所有方法对 {@code null}、非正数、NaN/Infinity 都返回 {@code null}，
 * 由调用方决定是否展示，避免把 {@code NaN} 或负数带进面向用户的文本。
 */
public final class PaceFormat {

    private PaceFormat() {
    }

    /** 秒/公里 -> 5'45"/km */
    public static String pace(Double secondsPerKm) {
        return paceWithUnit(secondsPerKm, "km");
    }

    /**
     * 秒/段单位 -> 5'45"/km 或 9'15"/mi。
     *
     * <p>华为的 {@code bpm2} 是英制（每英里）分段，同一次跑步的英里配速会明显大于公里配速，
     * 后缀必须跟着单位走，否则「9'15"/km」这种数字会被当成配速崩坏的证据。
     *
     * @param secondsPerUnit 每段单位的秒数
     * @param unit           {@code km} / {@code mi}，其它值按 {@code km} 渲染
     */
    public static String paceWithUnit(Double secondsPerUnit, String unit) {
        if (secondsPerUnit == null || secondsPerUnit <= 0 || !Double.isFinite(secondsPerUnit)) {
            return null;
        }
        long total = Math.round(secondsPerUnit);
        String suffix = "mi".equalsIgnoreCase(unit) ? "mi" : "km";
        return (total / 60) + "'" + String.format("%02d", total % 60) + "\"/" + suffix;
    }

    /** 公里/小时 -> 12.3 km/h */
    public static String speed(Double kmh) {
        if (kmh == null || kmh <= 0 || !Double.isFinite(kmh)) {
            return null;
        }
        return String.format("%.1f km/h", kmh);
    }

    /** 秒 -> 40 分 48 秒 / 1 小时 02 分 */
    public static String duration(Double seconds) {
        if (seconds == null || seconds <= 0 || !Double.isFinite(seconds)) {
            return null;
        }
        long total = Math.round(seconds);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        if (hours > 0) {
            return hours + " 小时 " + String.format("%02d", minutes) + " 分";
        }
        return minutes + " 分 " + String.format("%02d", secs) + " 秒";
    }

    /**
     * 秒 -> 22:47 / 3:35:26，不丢秒。
     *
     * <p>用于比赛成绩这类需要精确到秒的场合：{@link #duration(Double)} 在超过 1 小时后会丢掉秒，
     * 把 3:35:26 显示成「3 小时 35 分」，对成绩对比来说信息损失太大。
     */
    public static String clock(Double seconds) {
        if (seconds == null || seconds <= 0 || !Double.isFinite(seconds)) {
            return null;
        }
        long total = Math.round(seconds);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long secs = total % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, secs)
                : String.format("%d:%02d", minutes, secs);
    }

    /** 四舍五入到指定小数位；与 {@link Math#round(double)} 一致，负数按远离零方向取整。 */
    public static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
