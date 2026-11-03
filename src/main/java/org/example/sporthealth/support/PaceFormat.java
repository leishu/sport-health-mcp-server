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
        if (secondsPerKm == null || secondsPerKm <= 0 || !Double.isFinite(secondsPerKm)) {
            return null;
        }
        long total = Math.round(secondsPerKm);
        return (total / 60) + "'" + String.format("%02d", total % 60) + "\"/km";
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

    /** 四舍五入到指定小数位；与 {@link Math#round(double)} 一致，负数按远离零方向取整。 */
    public static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
