package org.example.sporthealth.weather;

/**
 * WMO 天气代码到中文描述的映射。
 *
 * <p>代码本身对人和大模型都没有意义（{@code 61} 是什么？），所以对外输出时一律附带文本。
 * open-meteo 的 {@code weather_code} 就是 WMO 4677 的简化子集，只覆盖晴、云、雾、毛毛雨、
 * 雨、雪、阵雨、雷暴这几族；未列出的代码按 {@code 未知(code)} 原样透出，不猜。
 *
 * <p>不做成枚举：接口以后新增代码时枚举会直接反序列化失败，而天气只是跑步记录的补充属性，
 * 不该因为一个未知代码让整条记录不可用。
 */
public final class WeatherCodes {

    private WeatherCodes() {
    }

    /**
     * @param code WMO 天气代码，可为 null
     * @return 中文描述；{@code null} 代码返回 null，未知代码返回 {@code 未知(code)}
     */
    public static String describe(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "晴间多云";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45 -> "雾";
            case 48 -> "冻雾";
            case 51 -> "小毛毛雨";
            case 53 -> "毛毛雨";
            case 55 -> "大毛毛雨";
            case 56 -> "冻毛毛雨";
            case 57 -> "强冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66 -> "冻雨";
            case 67 -> "强冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80 -> "小阵雨";
            case 81 -> "阵雨";
            case 82 -> "强阵雨";
            case 85 -> "小阵雪";
            case 86 -> "强阵雪";
            case 95 -> "雷暴";
            case 96 -> "雷暴伴小冰雹";
            case 99 -> "雷暴伴大冰雹";
            default -> "未知(" + code + ")";
        };
    }
}
