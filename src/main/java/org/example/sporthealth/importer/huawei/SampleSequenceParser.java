package org.example.sporthealth.importer.huawei;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 解析华为「Sample sequence data」导出。
 *
 * <p>每个文件是一个 JSON 数组，元素结构与健康明细类似，但 {@code samplePoints} 被替换成
 * 三段不透明文本：{@code summaryData}（小 JSON）、{@code detailData}、{@code extendData}。
 * 数值解读因 type 而异，属于分析层的事；导入层只负责原样保存，避免任何「看不懂就丢」。
 *
 * <p>用流式解析 + 回调：单个文件最大 6 MB，但一条 ECG 记录的 detailData 就有 15 万字符，
 * 全部驻留内存没有意义。
 */
@Component
public class SampleSequenceParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param fileName 来源文件名，仅用于异常信息
     * @param input    JSON 输入流
     * @param sink     每条记录回调一次
     */
    public void parse(String fileName, InputStream input, Consumer<SampleSequenceRecord> sink) {
        try (JsonParser parser = MAPPER.getFactory().createParser(input)) {
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new HuaweiDataException(fileName + ": 顶层不是 JSON 数组");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode node = MAPPER.readTree(parser);
                SampleSequenceRecord record = toRecord(node);
                if (record != null) {
                    sink.accept(record);
                }
            }
        } catch (IOException e) {
            throw new HuaweiDataException(fileName + ": 解析样本序列失败 -> " + e.getMessage(), e);
        }
    }

    private SampleSequenceRecord toRecord(JsonNode node) {
        String recordId = node.path("recordId").asText("");
        if (recordId.isBlank()) {
            return null;
        }
        int typeCode = node.path("type").asInt(0);
        if (typeCode <= 0) {
            return null;
        }
        Long startMillis = millis(node.path("startTime"));
        Long endMillis = millis(node.path("endTime"));
        if (startMillis == null) {
            startMillis = endMillis;
        }
        if (endMillis == null) {
            endMillis = startMillis;
        }
        if (startMillis == null) {
            return null;
        }
        return new SampleSequenceRecord(
                recordId,
                typeCode,
                longOrNull(node.path("dataId")),
                intOrNull(node.path("subType")),
                Instant.ofEpochMilli(startMillis),
                Instant.ofEpochMilli(endMillis),
                node.path("timeZone").asText(null),
                node.path("deviceCode").asText(null),
                node.path("summaryData").asText(null),
                node.path("detailData").asText(null),
                node.path("extendData").asText(null));
    }

    private static Long millis(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        long value = node.asLong(0);
        return value <= 0 ? null : value;
    }

    private static Long longOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        long value = node.asLong();
        return value <= 0 ? null : value;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }
}
