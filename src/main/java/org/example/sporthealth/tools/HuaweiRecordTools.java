package org.example.sporthealth.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.example.sporthealth.domain.EcgRecord;
import org.example.sporthealth.domain.Medal;
import org.example.sporthealth.domain.SampleSequence;
import org.example.sporthealth.repository.HuaweiRecordStore;
import org.example.sporthealth.support.DateParser;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * 华为导出里可以直接查询的「记录类」数据：勋章、ECG 元数据、样本序列。
 *
 * <p>这三张表是 v8 新增的「华为全量导出原样保留」数据，勋章与 ECG 元数据（波形原文在
 * 样本序列里）都太零散太重，不适合再做跑步分析；它们被这三个工具独立暴露更自然。
 * 勋章是 137 行、ECG 5 行、样本序列 11,790 行元数据——列表接口都有硬上限并通过
 * {@code hasMore} 标注没列完，别试图把一次调用的返回体挤爆上下文。
 */
@Component
@Slf4j
public class HuaweiRecordTools {

    private static final int MAX_MEDALS = 200;
    private static final int MAX_ECG_RECORDS = 100;
    private static final int MAX_SAMPLE_SEQUENCES = 100;

    private final HuaweiRecordStore store;

    public HuaweiRecordTools(HuaweiRecordStore store) {
        this.store = store;
    }

    /**
     * 勋章列表，{@code take_date} 降序（最近获得在前）。
     *
     * <p>同一类型 + 等级可能多次获得，本工具把同名勋章的旧行折叠掉——一次一行最新。
     */
    @McpTool(name = "get_medals",
            description = "查询华为体育勋章：类型、等级、获得次数与最近获得/修改时间。"
                    + "按 take_date 降序返回，最新获得的在前；不同类型（如 AAA / A2 / CCC）各自列出。"
                    + "用于回答“我这段时间拿了哪些勋章”“哪个等级最高”。")
    public Map<String, Object> getMedals(
            @McpToolParam(description = "勋章类型过滤，如 CCC、AAA；不传返回全部类型", required = false) String medalType,
            @McpToolParam(description = "勋章等级过滤，如 0、5、20000；不传返回全部等级", required = false) String medalLevel,
            @McpToolParam(description = "返回条数上限，默认 100，最多 200", required = false) Integer limit) {
        int cap = Math.min(limit == null || limit <= 0 ? MAX_MEDALS : limit, MAX_MEDALS);
        List<Medal> raw = store.medals(medalType, medalLevel, cap + 1);
        boolean hasMore = raw.size() > cap;
        return page("get_medals", raw.stream().limit(cap).toList(), hasMore);
    }

    /**
     * ECG 记录元数据，{@code start_time} 降序（最新在前）。
     *
     * <p>波形原文（每条 15,000 个采样）不进这张表；想看波形可以按 {@code recordId}
     * 用 {@code get_sample_sequences} 读原始数组（原文保留在 {@code sample_sequence}）。
     */
    @McpTool(name = "get_ecg_records",
            description = "查询华为 ECG 记录：平均心率、心律失常类型、数据长度、"
                    + "detail/extend 采样计数等元数据，按 start_time 降序。"
                    + "本库里只有 5 条记录；如需波形本身请按 recordId 查 get_sample_sequences。")
    public Map<String, Object> getEcgRecords(
            @McpToolParam(description = "返回条数上限，默认 100，最多 200", required = false) Integer limit) {
        int cap = Math.min(limit == null || limit <= 0 ? MAX_ECG_RECORDS : limit, MAX_ECG_RECORDS);
        List<EcgRecord> raw = store.ecgRecords(cap + 1);
        boolean hasMore = raw.size() > cap;
        if (raw.isEmpty()) {
            return noData();
        }
        return page("get_ecg_records", raw.stream().limit(cap).toList(), hasMore);
    }

    /**
     * 样本序列元数据：HRV RRI、心律失常、睡眠碎片、ECG 等原始样本的记录清单。
     *
     * <p>只拿元数据（记录 id、类型、开始结束时间）：11,790 条记录的原文动辄几十万
     * 字符，不分页拉回来没有意义。区间过滤用 {@code substr(start_time,1,10)} 宽松比较。
     */
    @McpTool(name = "get_sample_sequences",
            description = "查询华为「Sample sequence data」的记录：睡眠碎片（700008）、HRV RRI"
                    + "（700021）、心律失常（700004）、睡眠报告（700013）、放松训练（700014）等原始样本。"
                    + "只返回元数据：记录 id、类型码、开始/结束时间、时区与设备，全库 11,790 条。"
                    + "想按类型/日期过滤：type=700021 取HRV RRI、700008 取睡眠碎片。"
                    + "想看原始波形/数组原文，按本工具筛出的 recordId 到原始导出文件读取。")
    public Map<String, Object> getSampleSequences(
            @McpToolParam(description = "类型码过滤，如 700009、700013、700021、700008；不传返回全部类型",
                    required = false) String type,
            @McpToolParam(description = "开始日期 yyyy-MM-dd，默认取样本序列数据最早一天", required = false) String startDate,
            @McpToolParam(description = "结束日期 yyyy-MM-dd，默认取样本序列数据最新一天", required = false) String endDate,
            @McpToolParam(description = "返回条数上限，默认 100，最多 200", required = false) Integer limit) {
        LocalDate to = endDate != null && !endDate.isBlank()
                ? DateParser.parseDate(endDate.trim()) : LocalDate.now();
        LocalDate from = startDate != null && !startDate.isBlank()
                ? DateParser.parseDate(startDate.trim()) : null;
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期 " + from + " 晚于结束日期 " + to);
        }
        int cap = Math.min(limit == null || limit <= 0 ? MAX_SAMPLE_SEQUENCES : limit, MAX_SAMPLE_SEQUENCES);
        List<SampleSequence> raw = store.sampleSequences(type, from, to, cap + 1);
        boolean hasMore = raw.size() > cap;
        if (raw.isEmpty()) {
            return noData();
        }
        Map<String, Object> result = page("get_sample_sequences", raw.stream().limit(cap).toList(), hasMore);
        result.put("totalInRange", store.sampleSequenceCount(type, from, to));
        return result;
    }

    private <T> Map<String, Object> page(String tool, List<T> raw, boolean hasMore) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("tool", tool);
        result.put("total", raw.size());
        result.put("hasMore", hasMore);
        result.put("rows", raw);
        return result;
    }

    private Map<String, Object> noData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "NO_DATA");
        result.put("message", "没有匹配的记录");
        return result;
    }
}
