package org.example.sporthealth.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * 一次跑步的分段结果。
 *
 * <p>所有距离/配速都基于有效 GPS 轨迹计算，{@code gpsDistanceKm} 可能与
 * {@link RunActivity#distanceMeters()} 略有差异（轨迹采样与 Lap 汇总口径不同）。
 *
 * @param runId                      记录 id
 * @param date                       本地日期
 * @param splitMeters                实际使用的分段长度（米）
 * @param gpsDistanceKm              轨迹累积距离（公里）
 * @param movingDurationSeconds      首末有效轨迹点之间的时间（秒）
 * @param movingDurationText         易读时长
 * @param avgPaceSecondsPerKm        轨迹整体平均配速（秒/公里）
 * @param avgPaceText                易读配速
 * @param splits                     分段列表，最后一段可能不满
 * @param fastest                    最快的一段，无分段时为 null
 * @param slowest                    最慢的一段，无分段时为 null
 * @param firstHalfPaceSecondsPerKm  前半程配速（秒/公里）
 * @param secondHalfPaceSecondsPerKm 后半程配速（秒/公里）
 * @param negativeSplit              后半程是否更快；数据不足时为 null
 * @param paceStdDevSeconds          各整段配速的标准差（秒），越小越稳
 * @param avgGradeAdjustedPaceSecondsPerKm 全程坡度修正配速（秒/公里），无海拔数据时为 null
 * @param avgGradeAdjustedPaceText   全程坡度修正配速文本
 */
public record RunSplits(
        String runId,
        LocalDate date,
        double splitMeters,
        double gpsDistanceKm,
        double movingDurationSeconds,
        String movingDurationText,
        Double avgPaceSecondsPerKm,
        String avgPaceText,
        List<RunSplit> splits,
        RunSplit fastest,
        RunSplit slowest,
        Double firstHalfPaceSecondsPerKm,
        Double secondHalfPaceSecondsPerKm,
        Boolean negativeSplit,
        Double paceStdDevSeconds,
        Double avgGradeAdjustedPaceSecondsPerKm,
        String avgGradeAdjustedPaceText) {
}
