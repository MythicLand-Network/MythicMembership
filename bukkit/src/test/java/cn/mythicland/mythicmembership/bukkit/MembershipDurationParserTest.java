package cn.mythicland.mythicmembership.bukkit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MembershipDurationParserTest {
    @Test
    void shouldParseSupportedPositiveDurations() {
        assertEquals(Duration.ofSeconds(5L), MembershipDurationParser.parse("5s"));
        assertEquals(Duration.ofMinutes(3L), MembershipDurationParser.parse("3m"));
        assertEquals(Duration.ofHours(2L), MembershipDurationParser.parse("2h"));
        assertEquals(Duration.ofDays(30L), MembershipDurationParser.parse("30d"));
    }

    @Test
    void shouldRejectInvalidDurations() {
        assertThrows(IllegalArgumentException.class, () -> MembershipDurationParser.parse("0d"));
        assertThrows(IllegalArgumentException.class, () -> MembershipDurationParser.parse("1w"));
        assertThrows(IllegalArgumentException.class, () -> MembershipDurationParser.parse("day"));
    }
}
