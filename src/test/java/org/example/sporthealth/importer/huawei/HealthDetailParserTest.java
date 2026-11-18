package org.example.sporthealth.importer.huawei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 健康明细的取值规则与重复副本归一。 */
class HealthDetailParserTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void readsPlainNumericValue() {
        assertEquals(55.0, HealthDetailParser.extractNumber("55.0"));
    }

    @Test
    void readsSingleFieldObject() {
        assertEquals(55.0, HealthDetailParser.extractNumber("{\"bpm\":55}"));
        assertEquals(97.0, HealthDetailParser.extractNumber("{\"avgSaturation\":97.0}"));
    }

    @Test
    void prefersWhitelistFieldInMultiFieldObject() {
        assertEquals(119.0, HealthDetailParser.extractNumber(
                "{\"BLOOD_PRESSURE_SYSTOLIC\":119,\"BLOOD_PRESSURE_DIASTOLIC\":78}"));
        assertEquals(66.2, HealthDetailParser.extractNumber(
                "{\"bodyWeight\":66.2,\"bodyFatRate\":18.1,\"bmi\":22.0}"));
    }

    @Test
    void returnsNullWhenNoMeaningfulNumber() {
        assertNull(HealthDetailParser.extractNumber(""));
        assertNull(HealthDetailParser.extractNumber(null));
        assertNull(HealthDetailParser.extractNumber(
                "{\"accFlag\":0,\"algorithmVer\":8021,\"calibFlag\":0,\"devNo\":100}"),
                "只有元数据没有测量值时不该硬塞一个数");
        assertNull(HealthDetailParser.extractNumber("not json"));
    }

    @Test
    void normalisesDuplicatedMetricKeys() {
        String json = """
                [
                  {"recordId":"R1","type":7,"deviceCode":1,"timeZone":"+0800","startTime":1769902020000,
                   "samplePoints":[{"key":"DATA_POINT_DYNAMIC_HEARTRATE","startTime":1769902020000,
                                    "endTime":1769902020000,"unit":"0","value":"61.0"}]},
                  {"recordId":"R1","type":500023,"deviceCode":2,"timeZone":"+0800","startTime":1769902020000,
                   "samplePoints":[{"key":"DYNAMIC_HEART_RATE","startTime":1769902020000,
                                    "endTime":1769902020000,"unit":"0","value":"{\\"bpm\\":61}"}]}
                ]
                """;
        List<MetricSample> samples = parse(json);

        assertEquals(2, samples.size(), "两条记录都要产出采样点，去重交给主键");
        assertEquals("DYNAMIC_HEART_RATE", samples.get(0).metricKey());
        assertEquals("DYNAMIC_HEART_RATE", samples.get(1).metricKey());
        assertEquals(61.0, samples.get(0).valueNum());
        assertEquals(61.0, samples.get(1).valueNum());
    }

    @Test
    void normalisesRestingHeartRateCopies() {
        String json = """
                [
                  {"recordId":"R3","type":7,"deviceCode":1,"timeZone":"+0800","startTime":1769902020000,
                   "samplePoints":[{"key":"DATA_POINT_REST_HEARTRATE","startTime":1769902020000,
                                    "endTime":1769902020000,"unit":"0","value":"53.0"}]},
                  {"recordId":"R4","type":7,"deviceCode":1,"timeZone":"+0800","startTime":1769902080000,
                   "samplePoints":[{"key":"DATA_POINT_NEW_REST_HEARTRATE","startTime":1769902080000,
                                    "endTime":1769902080000,"unit":"0","value":"54.0"}]},
                  {"recordId":"R5","type":500024,"deviceCode":2,"timeZone":"+0800","startTime":1769902140000,
                   "samplePoints":[{"key":"RESTING_HEART_RATE","startTime":1769902140000,
                                    "endTime":1769902140000,"unit":"0","value":"{\\"bpm\\":55}"}]}
                ]
                """;
        List<MetricSample> samples = parse(json);

        assertEquals(3, samples.size());
        assertTrue(samples.stream().allMatch(sample -> "RESTING_HEART_RATE".equals(sample.metricKey())),
                "手机端的两个副本键都要归一到 RESTING_HEART_RATE: "
                        + samples.stream().map(MetricSample::metricKey).toList());
    }

    @Test
    void skipsRecordsWithoutSamplePoints() {
        List<MetricSample> samples = parse("""
                [{"recordId":"R2","type":9,"startTime":1769902020000,"samplePoints":[]}]
                """);

        assertEquals(0, samples.size());
    }

    private static List<MetricSample> parse(String json) {
        List<MetricSample> samples = new ArrayList<>();
        new HealthDetailParser().parse("test.json",
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), ZONE, samples::add);
        return samples;
    }
}
