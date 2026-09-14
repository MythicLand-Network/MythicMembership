package cn.mythicland.mythicmembership.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MembershipValueTest {
    @Test
    void keyAndScopeShouldNormalizeAndValidate() {
        assertEquals("myplugin:vip_plus", MembershipKey.of("MyPlugin", "VIP_PLUS").value());
        assertEquals("mythicthepit", MembershipScope.of("MythicThePit").value());
        assertThrows(IllegalArgumentException.class, () -> MembershipKey.parse("vip"));
        assertThrows(IllegalArgumentException.class, () -> MembershipScope.of("invalid scope"));
    }

    @Test
    void viewShouldFilterExpiryAndSelectHighestPriorityInScope() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-14T00:00:00Z");
        MembershipKey vip = MembershipKey.parse("test:vip");
        MembershipKey mvp = MembershipKey.parse("test:mvp");
        MembershipKey expired = MembershipKey.parse("test:expired");
        MembershipScope gameplay = MembershipScope.of("gameplay");
        MembershipView view = new MembershipView(
                playerId,
                List.of(
                        new MembershipRecord(playerId, vip, MembershipScope.NETWORK, null),
                        new MembershipRecord(playerId, mvp, gameplay, now.plusSeconds(1L)),
                        new MembershipRecord(playerId, expired, gameplay, now)
                ),
                Map.of(
                        vip, new MembershipDefinition(vip, MembershipScope.NETWORK, "VIP", "vip", 100),
                        mvp, new MembershipDefinition(mvp, gameplay, "MVP", "mvp", 200),
                        expired, new MembershipDefinition(expired, gameplay, "Expired", "", 300)
                ),
                now
        );

        assertTrue(view.active(vip));
        assertTrue(view.active(mvp));
        assertFalse(view.active(expired));
        assertEquals(mvp, view.primary(Set.of(MembershipScope.NETWORK, gameplay)).orElseThrow().record().key());
        assertEquals(vip, view.primary(Set.of(MembershipScope.NETWORK)).orElseThrow().record().key());
    }
}
