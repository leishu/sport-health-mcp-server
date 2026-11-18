package org.example.sporthealth.importer.huawei;

/**
 * 华为导出目录里会被导入的三类 JSON 文件。
 *
 * <p>识别方式是文件名前缀而不是目录名：导出包里目录名带 {@code & description} 后缀、还夹着空格，
 * 而文件名前缀稳定得多（例如 {@code motion path detail data1794280158626_0.json}）。
 * 认不出的 JSON（{@code user run plan data.json}、{@code user route data.json} 等空表）直接忽略。
 */
public enum HuaweiExportKind {

    /** 运动轨迹与汇总：190 次运动，含 GPS/心率/步频。 */
    MOTION("motion path detail data", "运动轨迹"),

    /** 健康明细：心率、睡眠、血氧、压力等逐点指标。 */
    HEALTH("health detail data", "健康明细"),

    /** 每分钟运动数据：步数、距离、热量、站立。 */
    MINUTE("sport per minute merged data", "每分钟运动数据");

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

    /** 按文件名前缀识别；不是已知类型时返回 null。 */
    public static HuaweiExportKind of(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (HuaweiExportKind kind : values()) {
            if (lower.startsWith(kind.fileNamePrefix)) {
                return kind;
            }
        }
        return null;
    }
}
