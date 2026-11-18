package org.example.sporthealth.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class DateParserTest {

    @Test
    void parsesCommonDateFormats() {
        assertThat(DateParser.parseDate("2026-11-03")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(DateParser.parseDate("2026/11/3")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(DateParser.parseDate("2026-11-03 08:30:00")).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void parsesDateTimeFormats() {
        assertThat(DateParser.parseDateTime("2026-11-03 08:30:00"))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 30));
        assertThat(DateParser.parseDateTime("2026-11-03T08:30:00"))
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 8, 30));
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> DateParser.parseDate("not-a-date"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }
}
