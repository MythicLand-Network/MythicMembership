package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.mythicmembership.api.MembershipKey;
import cn.mythicland.mythicmembership.api.MembershipScope;
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

class MembershipRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    private Connection keeper;
    private MembershipRepository repository;

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
        this.repository = new MembershipRepository(dataSource, DatabaseMode.SQLITE);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.keeper.close();
    }

    @Test
    void setExtendPermanentAndRevokeShouldShareOneContract() {
        UUID playerId = UUID.randomUUID();
        MembershipKey key = MembershipKey.parse("test:vip");
        Instant initial = Instant.now().plusSeconds(60L);

        assertEquals(initial, this.repository.set(playerId, key, MembershipScope.NETWORK, initial).expiresAt());
        assertEquals(
                initial.plusSeconds(60L).toEpochMilli(),
                this.repository.extend(
                                playerId,
                                key,
                                MembershipScope.NETWORK,
                                initial.minusSeconds(10L).toEpochMilli(),
                                60_000L
                        )
                        .expiresAt().toEpochMilli()
        );
        assertTrue(this.repository.set(playerId, key, MembershipScope.NETWORK, null).permanent());
        assertThrows(
                IllegalStateException.class,
                () -> this.repository.extend(
                        playerId,
                        key,
                        MembershipScope.NETWORK,
                        System.currentTimeMillis(),
                        60_000L
                )
        );
        assertTrue(this.repository.revoke(playerId, key, MembershipScope.NETWORK));
        assertFalse(this.repository.revoke(playerId, key, MembershipScope.NETWORK));
    }

    @Test
    void historicalNameShouldResolvePlayer() {
        UUID playerId = UUID.randomUUID();
        this.repository.rememberName(playerId, "Mythic");

        assertEquals(playerId, this.repository.findPlayer("mythic").orElseThrow());
        assertEquals(playerId, this.repository.findPlayer(playerId.toString()).orElseThrow());
    }
}
