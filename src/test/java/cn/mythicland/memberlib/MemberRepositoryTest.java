package cn.mythicland.memberlib;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemberRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private Connection keeper;
    private MemberRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + this.temporaryDirectory.resolve("membership.db"));
        this.keeper = dataSource.getConnection();
        try (Statement statement = this.keeper.createStatement()) {
            statement.execute("""
                    CREATE TABLE mythic_membership (
                        player_uuid TEXT NOT NULL,
                        membership_id TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        expires_at_millis BIGINT NULL,
                        PRIMARY KEY (player_uuid, membership_id, scope)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE mythic_membership_player_name (
                        player_name_lower TEXT PRIMARY KEY,
                        player_name TEXT NOT NULL,
                        player_uuid TEXT NOT NULL,
                        last_seen_millis BIGINT NOT NULL
                    )
                    """);
        }
        this.repository = new MemberRepository(dataSource, DatabaseMode.SQLITE);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.keeper.close();
    }

    @Test
    void setExtendPermanentAndRevokeShouldShareOneContract() {
        UUID playerId = UUID.randomUUID();
        MemberKey key = MemberKey.parse("test:vip");
        Instant initial = Instant.now().plusSeconds(60L);

        assertEquals(initial, this.repository.set(playerId, key, MemberScope.NETWORK, initial).expiresAt());
        assertEquals(
                initial.plusSeconds(60L).toEpochMilli(),
                this.repository.extend(
                                playerId,
                                key,
                                MemberScope.NETWORK,
                                initial.minusSeconds(10L).toEpochMilli(),
                                60_000L
                        )
                        .expiresAt().toEpochMilli()
        );
        assertTrue(this.repository.set(playerId, key, MemberScope.NETWORK, null).permanent());
        assertThrows(
                IllegalStateException.class,
                () -> this.repository.extend(
                        playerId,
                        key,
                        MemberScope.NETWORK,
                        System.currentTimeMillis(),
                        60_000L
                )
        );
        assertTrue(this.repository.revoke(playerId, key, MemberScope.NETWORK));
        assertFalse(this.repository.revoke(playerId, key, MemberScope.NETWORK));
    }

    @Test
    void activeMembersShouldExcludeExpiredRecordsAndOtherKeys() {
        MemberKey key = MemberKey.parse("test:room");
        UUID active = UUID.randomUUID();
        UUID permanent = UUID.randomUUID();
        this.repository.extend(active, key, MemberScope.NETWORK, System.currentTimeMillis(), 60_000);
        this.repository.set(permanent, key, MemberScope.NETWORK, null);
        this.repository.set(UUID.randomUUID(), key, MemberScope.NETWORK, Instant.now().minusSeconds(1));
        this.repository.set(UUID.randomUUID(), MemberKey.parse("test:vip"), MemberScope.NETWORK, null);

        assertEquals(java.util.Set.of(active, permanent), this.repository.activeMembers(key, MemberScope.NETWORK).stream()
                .map(MemberRecord::playerUniqueId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void historicalNameShouldResolvePlayer() {
        UUID playerId = UUID.randomUUID();
        this.repository.rememberName(playerId, "Mythic");

        assertEquals(playerId, this.repository.findPlayer("mythic").orElseThrow());
        assertEquals(playerId, this.repository.findPlayer(playerId.toString()).orElseThrow());
    }
}
