package org.example.sporthealth.support;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 华为 {@code detailData} 里没进轨迹点的原始流类型目录：语义、单位与等价别名。
 *
 * <p>这些流的字段含义不在华为的公开文档里，全部来自对导出样本的实测，因此这里只写
 * 「验证过什么」，未确认的直接标「未确认」，不猜。新增流类型时补一行即可。
 */
public final class HuaweiStreamTypes {

    /**
     * @param meaning    这一行代表什么
     * @param unit       主数值单位；{@code null} 表示没有单一数值
     * @param equivalent 与它数值完全等价的另一种写法（同一次运动里可能两份都在）
     */
    public record Kind(String meaning, String unit, String equivalent) {
    }

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("rs", new Kind("每 5 秒一次的速度采样，k 是轨迹点序号而不是时间",
                "v 的单位约 0.1 m/s（×0.36 得 km/h，实测与活动平均速度吻合）", null));
        KINDS.put("rp", new Kind("跑姿采样：触地时间 gct(ms)、垂直振幅 vo(cm)、左右平衡 gtb(%)、"
                + "腾空时间与摆臂等；-1 / -101 表示该机型未采集", "混合，见字段名后缀", null));
        KINDS.put("pad", new Kind("语义未确认：按毫秒时间戳采样，v 在 0~38 之间", "未确认", null));
        KINDS.put("pm2", new Kind("华为官方单公里配速：d 是段终点距离(米)、n 是终点轨迹点序号、"
                + "p 是该段配速(秒/公里)", "秒/公里", "p-m"));
        KINDS.put("p-m", new Kind("pm2 的扁平写法：k = 距离米×10000 + 点序号，v = 配速",
                "秒/公里", "pm2"));
        KINDS.put("bpm2", new Kind("与同一段的 pm2 恒差 204~224 秒，语义未确认（既不等于累计用时，"
                + "也不是另一种口径的配速）", "未确认", "b-p-m"));
        KINDS.put("b-p-m", new Kind("bpm2 的扁平写法：k = 距离米×10000 + 点序号，v = 该数值",
                "未确认", "bpm2"));
        KINDS.put("scp", new Kind("语义未确认：按毫秒时间戳采样，v 在 0~176 之间", "未确认", null));
        KINDS.put("i-h-r", new Kind("瞬时心率采集，绝大多数行 v=-1（该次未采集）", "bpm", null));
        KINDS.put("evt", new Kind("事件行：e 恒为 0，ty 取 0/1（疑似开始/结束），st 是毫秒时间戳",
                null, null));
        KINDS.put("unknown", new Kind("解析不出 tp 的原始行，内容在 _raw 里（例如 lbs 行缺字段）",
                null, null));
    }

    private HuaweiStreamTypes() {
    }

    /** 归一化类型名：去空格、转小写，{@code P-M} 与 {@code p-m} 视为同一类。 */
    public static String canonical(String type) {
        return type == null ? null : type.trim().toLowerCase(Locale.ROOT);
    }

    public static Kind of(String type) {
        Kind kind = KINDS.get(canonical(type));
        return kind == null
                ? new Kind("未收录的流类型，字段名见 data 原文", null, null)
                : kind;
    }

    /** 等价写法的类型名；没有等价写法时返回 {@code null}。 */
    public static String equivalent(String type) {
        return of(type).equivalent();
    }
}
