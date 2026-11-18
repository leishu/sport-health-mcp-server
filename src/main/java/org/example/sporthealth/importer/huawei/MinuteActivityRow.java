package org.example.sporthealth.importer.huawei;

import java.time.LocalDateTime;

/**
 * 华为「每分钟运动数据」的一行，对应 {@code activity_minute}。
 *
 * <p>华为把一天的步数、距离、热量按分钟切成记录，同一天可能同时存在多条不同
 * {@code sportType} 的记录（例如走路 + 跑步 + 站立）。主键取 {@code (start_time, sport_type)}，
 * 因此按天汇总时不会因为设备合并而重复计数。
 *
 * @param startTime       该分钟开始（本地时间）
 * @param endTime         该分钟结束（本地时间）
 * @param sportType       5=走路、4=跑步、13=站立、2=其它
 * @param recordDay       导出里的 {@code recordDay}，形如 20251108
 * @param steps           步数
 * @param distanceMeters  距离（米）
 * @param caloriesKcal    热量（千卡），由导出的 cal 除以 1000 换算
 * @param durationMinutes 时长（分钟，通常为 1）
 * @param isIntensity     是否中高强度（1/0）
 * @param isStand         是否站立（1/0）
 * @param altitude        海拔（米）
 * @param deviceCode      采集设备编码
 */
public record MinuteActivityRow(
        LocalDateTime startTime,
        LocalDateTime endTime,
        int sportType,
        int recordDay,
        Integer steps,
        Integer distanceMeters,
        Double caloriesKcal,
        Integer durationMinutes,
        Integer isIntensity,
        Integer isStand,
        Double altitude,
        String deviceCode) {
}
