package org.example.sporthealth.importer.huawei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SampleSequenceParserTest {

    private final SampleSequenceParser parser = new SampleSequenceParser();

    @Test
    void keepsRawPayloadsAndMetadata() {
        String json = """
                [
                  {"dataId":281,"type":700013,"subType":0,"deviceCode":2374625270,
                   "recordId":"SLEEP-1","startTime":1781274720000,"endTime":1781299620000,
                   "timeZone":"+0800","summaryData":"{\\"sleepScore\\":75}",
                   "detailData":"68010000","extendData":""},
                  {"type":700009,"recordId":"ECG-1","startTime":1781244495000,
                   "endTime":1781244525000,"summaryData":"{\\"averageHeartRate\\":51}",
                   "detailData":"[1.0,2.0]","extendData":"[3.0]"}
                ]
                """;
        List<SampleSequenceRecord> records = parse(json);

        assertEquals(2, records.size());
        SampleSequenceRecord sleep = records.get(0);
        assertEquals("SLEEP-1", sleep.recordId());
        assertEquals(700013, sleep.typeCode());
        assertEquals(Instant.ofEpochMilli(1781274720000L), sleep.startTime());
        assertEquals("+0800", sleep.timeZone());
        assertEquals("{\"sleepScore\":75}", sleep.summaryData());
        assertEquals("68010000", sleep.detailData());

        SampleSequenceRecord ecg = records.get(1);
        assertEquals(700009, ecg.typeCode());
        assertNull(ecg.timeZone(), "缺失字段保持 null，不编造默认值");
        assertEquals("[1.0,2.0]", ecg.detailData());
        assertEquals("[3.0]", ecg.extendData());
    }

    @Test
    void skipsRecordsWithoutRecordIdOrType() {
        String json = """
                [
                  {"type":700013,"startTime":1781274720000},
                  {"recordId":"NO-TYPE","startTime":1781274720000},
                  {"recordId":"NO-TIME","type":700013}
                ]
                """;
        assertEquals(0, parse(json).size());
    }

    private List<SampleSequenceRecord> parse(String json) {
        List<SampleSequenceRecord> records = new ArrayList<>();
        parser.parse("sample sequence data1_0.json",
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), records::add);
        return records;
    }
}
