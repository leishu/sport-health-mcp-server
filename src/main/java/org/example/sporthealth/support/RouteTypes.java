package org.example.sporthealth.support;

/**
 * 华为 TCX 中 {@code Lap/Extensions/RouteType} 的语义映射。
 *
 * <p>华为导出里该字段是裸数字。当前样本（5 个户外跑步文件）全部为 {@code 0}，
 * 结合 Sport 字段可确定 {@code 0} 表示户外轨迹；{@code 1} 按同类导出惯例视为室内。
 * 未识别的值不丢弃，原样保留在 {@link #text(String)} 的返回值里，便于后续补充映射。
 */
public final class RouteTypes {

    public static final String OUTDOOR = "OUTDOOR";
    public static final String INDOOR = "INDOOR";
    public static final String UNKNOWN_PREFIX = "UNKNOWN";

    private RouteTypes() {
    }

    public static String text(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim()) {
            case "0" -> OUTDOOR;
            case "1" -> INDOOR;
            default -> UNKNOWN_PREFIX + "(" + raw.trim() + ")";
        };
    }
}
