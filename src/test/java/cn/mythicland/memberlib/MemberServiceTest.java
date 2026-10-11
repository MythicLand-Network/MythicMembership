package cn.mythicland.memberlib;

import org.bukkit.plugin.java.JavaPlugin;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberServiceTest {
    @Test
    void readinessShouldTrackLoadAndEvictionWithoutHidingMissingViews() {
        UUID playerId = UUID.randomUUID();
        MemberRepository repository = mock(MemberRepository.class);
        when(repository.load(playerId)).thenReturn(List.of());

        try (MemberService service = new MemberService(mock(JavaPlugin.class))) {
            service.connect(repository);
            assertFalse(service.isLoaded(playerId));
            assertThrows(IllegalStateException.class, () -> service.view(playerId));
            assertEquals(List.of(), service.lookup(playerId).join().records());
            assertFalse(service.isLoaded(playerId));
            service.load(playerId).join();
            assertTrue(service.isLoaded(playerId));
            MemberRecord offlineRecord = new MemberRecord(playerId, MemberKey.of("test", "member"), MemberScope.NETWORK, null);
            when(repository.load(playerId)).thenReturn(List.of(offlineRecord));
            assertEquals(List.of(offlineRecord), service.lookup(playerId).join().records());
            assertEquals(List.of(), service.view(playerId).records());
            service.evict(playerId);
            assertFalse(service.isLoaded(playerId));
            assertThrows(IllegalStateException.class, () -> service.view(playerId));
        }
    }

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
