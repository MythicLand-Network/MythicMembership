package cn.mythicland.memberlib;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MemberDurationParserTest {
    @Test
    void shouldParseSupportedPositiveDurations() {
        assertEquals(Duration.ofSeconds(5L), MemberDurationParser.parse("5s"));
        assertEquals(Duration.ofMinutes(3L), MemberDurationParser.parse("3m"));
        assertEquals(Duration.ofHours(2L), MemberDurationParser.parse("2h"));
        assertEquals(Duration.ofDays(30L), MemberDurationParser.parse("30d"));
    }

    @Test
    void shouldRejectInvalidDurations() {
        assertThrows(IllegalArgumentException.class, () -> MemberDurationParser.parse("0d"));
        assertThrows(IllegalArgumentException.class, () -> MemberDurationParser.parse("1w"));
        assertThrows(IllegalArgumentException.class, () -> MemberDurationParser.parse("day"));
    }
}
