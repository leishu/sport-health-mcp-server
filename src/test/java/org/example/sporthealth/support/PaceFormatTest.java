package org.example.sporthealth.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PaceFormatTest {

    @Test
    void formatsPace() {
        assertEquals("5'45\"/km", PaceFormat.pace(345.0));
        assertEquals("6'00\"/km", PaceFormat.pace(360.0));
        assertNull(PaceFormat.pace(null));
        assertNull(PaceFormat.pace(0.0));
    }

    @Test
    void formatsSpeed() {
        assertEquals("12.3 km/h", PaceFormat.speed(12.34));
        assertNull(PaceFormat.speed(null));
    }

    @Test
    void formatsDuration() {
        assertEquals("40 分 48 秒", PaceFormat.duration(2448.0));
        assertEquals("1 小时 01 分", PaceFormat.duration(3696.0));
        assertNull(PaceFormat.duration(null));
    }
}
