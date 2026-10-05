package cn.mythicland.memberlib;

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

class MemberValueTest {
    @Test
    void keyAndScopeShouldNormalizeAndValidate() {
        assertEquals("myplugin:vip_plus", MemberKey.of("MyPlugin", "VIP_PLUS").value());
        assertEquals("mythicthepit", MemberScope.of("MythicThePit").value());
        assertThrows(IllegalArgumentException.class, () -> MemberKey.parse("vip"));
        assertThrows(IllegalArgumentException.class, () -> MemberScope.of("invalid scope"));
    }

    @Test
    void viewShouldFilterExpiryAndSelectHighestPriorityInScope() {
        UUID playerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-14T00:00:00Z");
        MemberKey vip = MemberKey.parse("test:vip");
        MemberKey mvp = MemberKey.parse("test:mvp");
        MemberKey expired = MemberKey.parse("test:expired");
        MemberScope gameplay = MemberScope.of("gameplay");
        MemberView view = new MemberView(
                playerId,
                List.of(
                        new MemberRecord(playerId, vip, MemberScope.NETWORK, null),
                        new MemberRecord(playerId, mvp, gameplay, now.plusSeconds(1L)),
                        new MemberRecord(playerId, expired, gameplay, now)
                ),
                Map.of(
                        vip, new MemberDefinition(vip, MemberScope.NETWORK, "VIP", "vip", 100),
                        mvp, new MemberDefinition(mvp, gameplay, "MVP", "mvp", 200),
                        expired, new MemberDefinition(expired, gameplay, "Expired", "", 300)
                ),
                now
        );

        assertTrue(view.active(vip));
        assertTrue(view.active(mvp));
        assertFalse(view.active(expired));
        assertEquals(mvp, view.primary(Set.of(MemberScope.NETWORK, gameplay)).orElseThrow().record().key());
        assertEquals(vip, view.primary(Set.of(MemberScope.NETWORK)).orElseThrow().record().key());
    }
}
