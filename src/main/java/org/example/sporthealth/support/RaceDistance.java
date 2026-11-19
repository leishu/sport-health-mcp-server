package org.example.sporthealth.support;

/**
 * 比赛距离的中文叫法。
 *
 * <p>5 公里、半程马拉松、全程马拉松这些档位在成绩预测、官方跑力、最佳努力里反复出现，
 * 集中一处避免同一距离在不同工具里写法不一致（例如「半马」与「半程马拉松」混用）。
 */
public final class RaceDistance {

    private RaceDistance() {
    }

    /**
     * 距离（米）-> 易读叫法；非标准距离回退成「12.5 公里」。
     *
     * @param meters 距离（米）
     */
    public static String label(double meters) {
        if (Math.abs(meters - 1000d) < 1d) {
            return "1 公里";
        }
        if (Math.abs(meters - 3000d) < 1d) {
            return "3 公里";
        }
        if (Math.abs(meters - 5000d) < 1d) {
            return "5 公里";
        }
        if (Math.abs(meters - 10000d) < 1d) {
            return "10 公里";
        }
        if (Math.abs(meters - 21097.5d) < 1d) {
            return "半程马拉松";
        }
        if (Math.abs(meters - 42195d) < 1d) {
            return "全程马拉松";
        }
        double km = PaceFormat.round(meters / 1000d, 2);
        // 12 公里不要写成 12.0 公里
        String text = km == Math.rint(km) ? String.valueOf((long) km) : String.valueOf(km);
        return text + " 公里";
    }
}
