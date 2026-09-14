package cn.mythicland.mythicmembership.bukkit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MembershipServiceTest {
    @Test
    void serviceShouldStartWithoutMembershipDefinitions() {
        MythicMembershipPlugin plugin = mock(MythicMembershipPlugin.class);
        MembershipRepository repository = mock(MembershipRepository.class);

        try (MembershipService service = new MembershipService(plugin, repository)) {
            assertTrue(service.definitions().isEmpty());
        }
    }
}
