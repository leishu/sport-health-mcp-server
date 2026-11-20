package org.example.sporthealth.domain;

/**
 * 华为自己算的一段配速：{@code pm2} 的整公里段或 {@code bpm2} 的整英里段。
 *
 * <p>与 {@link RunSplit} 的区别：{@link RunSplit} 由 GPS 轨迹插值切分，这里是手表/App 自己的
 * 分段结果——切分依据是设备累计距离，段内不插值，所以同样 1 公里会得到不同的配速。
 * 两者只用于互相校验，不能互相替代。
 *
 * <p>原始行里 {@code d} 的单位是「千分之一（米 / 英里）」，{@code p} 是「秒/段单位」：
 * {@code pm2} 的 {@code d=2000;p=346.0} 表示「到 2 公里为止，本公里用了 346 秒」；
 * {@code bpm2} 的 {@code d=2000;p=593.1} 表示「到 2 英里为止，本英里用了 593.1 秒」。
 * 末段不足一个单位时 {@code p} 仍是配速而不是用时，所以用时一律按
 * {@code p × 本段距离 / 段单位长度} 折算。
 *
 * @param index              段序号，从 1 开始
 * @param unit               {@code km} 或 {@code mile}
 * @param fromKm             起始距离（公里）
 * @param toKm               结束距离（公里）
 * @param distanceKm         本段距离（公里）
 * @param durationSeconds    本段用时（秒），由配速 × 距离折算
 * @param paceSecondsPerUnit 本段配速（秒/段单位）：{@code km} 是秒/公里，{@code mile} 是秒/英里
 * @param paceText           按段单位渲染的配速文本，例如 {@code 5'45"/km}、{@code 9'15"/mi}
 * @param paceSecondsPerKm   统一换算成秒/公里，便于公里与英里两种口径横向比较
 * @param paceTextPerKm      秒/公里口径的配速文本
 * @param partial            是否不满一个段单位（末段常见）
 * @param pointIndex         华为在该段终点处的轨迹点序号，缺失时为 null
 */
public record OfficialSplit(
        int index,
        String unit,
        double fromKm,
        double toKm,
        double distanceKm,
        double durationSeconds,
        double paceSecondsPerUnit,
        String paceText,
        double paceSecondsPerKm,
        String paceTextPerKm,
        boolean partial,
        Integer pointIndex) {
}
