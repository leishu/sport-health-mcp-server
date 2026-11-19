package org.example.sporthealth.importer.huawei;

import java.util.Locale;

/**
 * 华为导出目录里会被导入的文件类别。
 *
 * <p>JSON 用文件名前缀识别，而不是目录名：导出包里目录名带 {@code & description} 后缀、
 * 还夹着空格，而文件名前缀稳定得多（例如 {@code motion path detail data1794280158626_0.json}）。
 * 表格文件用扩展名识别，并排除官方字段说明（文件名含 {@code description}）。
 * 认不出的文件（{@code user run plan data.json}、{@code user route data.json} 等空表）直接忽略。
 */
public enum HuaweiExportKind {

    /** 运动轨迹与汇总：190 次运动，含 GPS/心率/步频。 */
    MOTION("motion path detail data", "运动轨迹"),

    /** 健康明细：心率、睡眠、血氧、压力等逐点指标。 */
    HEALTH("health detail data", "健康明细"),

    /** 每分钟运动数据：步数、距离、热量、站立。 */
    MINUTE("sport per minute merged data", "每分钟运动数据"),

    /** 原始样本序列：HRV RRI、心律失常、睡眠报告、ECG 波形等。 */
    SAMPLE_SEQUENCE("sample sequence data", "样本序列"),

    /** SportsHealth 导出的 xls：每日统计、跑力、勋章等。 */
    SPORTSHEALTH_XLS("", "SportsHealth 表格"),

    /** HUAWEI_Research 的汇总 xlsx：DailyWorkout、SleepEpisode 等。 */
    RESEARCH_XLSX("", "研究汇总表格");

    private final String fileNamePrefix;
    private final String displayName;

    HuaweiExportKind(String fileNamePrefix, String displayName) {
        this.fileNamePrefix = fileNamePrefix;
        this.displayName = displayName;
    }

    public String fileNamePrefix() {
        return fileNamePrefix;
    }

    public String displayName() {
        return displayName;
    }

    /** 按文件名识别；不是已知类型时返回 null。 */
    public static HuaweiExportKind of(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xls")) {
            return isDescription(lower) ? null : SPORTSHEALTH_XLS;
        }
        if (lower.endsWith(".xlsx")) {
            return isDescription(lower) ? null : RESEARCH_XLSX;
        }
        for (HuaweiExportKind kind : values()) {
            if (!kind.fileNamePrefix.isEmpty() && lower.startsWith(kind.fileNamePrefix)) {
                return kind;
            }
        }
        return null;
    }

    /** 官方字段说明表格不是数据，导入它只会得到一堆说明文字。 */
    private static boolean isDescription(String lowerFileName) {
        return lowerFileName.contains("description");
    }
}
