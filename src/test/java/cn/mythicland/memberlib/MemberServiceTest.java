package cn.mythicland.memberlib;

import org.bukkit.plugin.java.JavaPlugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberServiceTest {
    @Test
    void viewsShouldObserveDefinitionChangesWithoutChangingEarlierSnapshots() {
        MemberRepository repository = mock(MemberRepository.class);
        UUID playerUniqueId = UUID.randomUUID();
        MemberKey key = MemberKey.of("test", "member");
        MemberDefinition definition = new MemberDefinition(key, MemberScope.NETWORK, "会员", "", 1);
        when(repository.load(playerUniqueId)).thenReturn(List.of(
                new MemberRecord(playerUniqueId, key, MemberScope.NETWORK, null)
        ));

        try (MemberService service = new MemberService(mock(JavaPlugin.class))) {
            service.connect(repository);
            MemberView before = service.load(playerUniqueId).join();
            MemberRegistration registration = service.register(definition);
            MemberView registered = service.view(playerUniqueId);
            assertTrue(before.primary(Set.of(MemberScope.NETWORK)).isEmpty());
            assertEquals(definition, registered.primary(Set.of(MemberScope.NETWORK)).orElseThrow().definition());
            assertThrows(IllegalStateException.class, () -> service.register(definition));

            registration.close();
            registration.close();
            assertTrue(service.view(playerUniqueId).primary(Set.of(MemberScope.NETWORK)).isEmpty());
            assertEquals(definition, registered.primary(Set.of(MemberScope.NETWORK)).orElseThrow().definition());
            try (MemberRegistration replacement = service.register(definition)) {
                assertTrue(service.view(playerUniqueId).primary(Set.of(MemberScope.NETWORK)).isPresent());
            }
        }
    }

    @Test
    void serviceShouldStartWithoutMemberDefinitions() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MemberRepository repository = mock(MemberRepository.class);

        try (MemberService service = new MemberService(plugin)) {
            service.connect(repository);
            assertTrue(service.definitions().isEmpty());
        }
    }
}
