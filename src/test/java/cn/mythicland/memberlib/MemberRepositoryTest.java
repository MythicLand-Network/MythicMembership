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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                        paused_by TEXT NULL,
                        paused_remaining_millis BIGINT NULL,
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
    void replacementShouldPausePreviousMembershipsAndStartTheNewMembershipAtFullDuration() {
        UUID player = UUID.randomUUID(), otherPlayer = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        MemberDefinition mvp = new MemberDefinition(MemberKey.parse("test:mvp"), MemberScope.NETWORK, "MVP", "", 2);
        MemberKey target = MemberKey.parse("test:mvp_plus");
        MemberKey pit = MemberKey.parse("test:pit");
        MemberScope pitScope = MemberScope.of("pit");
        long now = System.currentTimeMillis();
        long duration = Duration.ofDays(30).toMillis();
        this.repository.set(player, vip.key(), vip.scope(), null);
        this.repository.set(player, mvp.key(), mvp.scope(), Instant.ofEpochMilli(now + duration));
        this.repository.set(player, pit, pitScope, null);
        this.repository.set(otherPlayer, vip.key(), vip.scope(), null);

        MemberRecord result = this.repository.extend(player, target, MemberScope.NETWORK, now, duration, List.of(vip, mvp));

        assertEquals(Instant.ofEpochMilli(now + duration), result.expiresAt());
        assertEquals(List.of(result), this.repository.load(player).stream().filter(record -> record.key().equals(target)).toList());
        List<MemberRecord> scheduled = MemberTimeline.resolve(this.repository.load(player));
        MemberRecord coveredMvp = scheduled.stream().filter(record -> record.key().equals(mvp.key())).findFirst().orElseThrow();
        MemberRecord coveredVip = scheduled.stream().filter(record -> record.key().equals(vip.key())).findFirst().orElseThrow();
        assertEquals(result.expiresAt(), coveredMvp.pause().resumesAt());
        assertEquals(result.expiresAt().plusMillis(duration), coveredVip.pause().resumesAt());
        assertFalse(coveredMvp.activeAt(Instant.ofEpochMilli(now)));
        assertTrue(coveredVip.activeAt(coveredVip.pause().resumesAt()));
        assertTrue(this.repository.activeMembers(vip.key(), vip.scope()).stream().noneMatch(record -> record.playerUniqueId().equals(player)));
        assertEquals(4, this.repository.load(player).size());
        assertTrue(this.repository.load(player).stream().anyMatch(record -> record.key().equals(pit) && record.permanent()));
        assertEquals(vip.key(), this.repository.load(otherPlayer).getFirst().key());
    }

    @Test
    void replacementFailureShouldRollBackBothTheNewMembershipAndEarlierPauses() throws Exception {
        UUID player = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        MemberDefinition mvp = new MemberDefinition(MemberKey.parse("test:mvp"), MemberScope.NETWORK, "MVP", "", 2);
        this.repository.set(player, vip.key(), vip.scope(), null);
        this.repository.set(player, mvp.key(), mvp.scope(), null);
        List<MemberRecord> before = this.repository.load(player);
        try (Statement statement = this.keeper.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_replacement BEFORE UPDATE ON mythic_membership
                    WHEN OLD.membership_id = 'test:vip'
                    BEGIN SELECT RAISE(ABORT, 'replacement failed'); END
                    """);
        }

        assertThrows(IllegalStateException.class, () -> this.repository.extend(player,
                MemberKey.parse("test:mvp_plus"), MemberScope.NETWORK, System.currentTimeMillis(),
                Duration.ofDays(30).toMillis(), List.of(mvp, vip)));

        assertEquals(before, this.repository.load(player));
    }

    @Test
    void replacementShouldGrantOrExtendTheGiftAndPreservePermanentMembership() {
        MemberKey rank = MemberKey.parse("test:mvp");
        MemberDefinition gift = new MemberDefinition(MemberKey.parse("test:pit"), MemberScope.of("pit"), "Pit", "", 1);
        long now = System.currentTimeMillis();
        long duration = Duration.ofDays(30).toMillis();
        for (int previousDays : List.of(0, 10, -10)) {
            UUID player = UUID.randomUUID();
            if (previousDays != 0) this.repository.set(player, gift.key(), gift.scope(),
                    Instant.ofEpochMilli(now).plus(Duration.ofDays(previousDays)));

            this.repository.extend(player, rank, MemberScope.NETWORK, now, duration, List.of(), List.of(gift));

            MemberRecord record = this.repository.load(player).stream()
                    .filter(member -> member.key().equals(gift.key())).findFirst().orElseThrow();
            assertEquals(Instant.ofEpochMilli(now).plus(Duration.ofDays(Math.max(0, previousDays) + 30)), record.expiresAt());
        }
        UUID permanent = UUID.randomUUID();
        this.repository.set(permanent, gift.key(), gift.scope(), null);
        this.repository.extend(permanent, rank, MemberScope.NETWORK, now, duration, List.of(), List.of(gift));
        assertTrue(this.repository.load(permanent).stream().anyMatch(record -> record.key().equals(gift.key()) && record.permanent()));
    }

    @Test
    void giftFailureShouldRollBackRankPurchaseAndPausedMemberships() throws Exception {
        UUID player = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        MemberDefinition gift = new MemberDefinition(MemberKey.parse("test:pit"), MemberScope.of("pit"), "Pit", "", 1);
        this.repository.set(player, vip.key(), vip.scope(), null);
        List<MemberRecord> before = this.repository.load(player);
        try (Statement statement = this.keeper.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_gift BEFORE INSERT ON mythic_membership
                    WHEN NEW.membership_id = 'test:pit'
                    BEGIN SELECT RAISE(ABORT, 'gift failed'); END
                    """);
        }

        assertThrows(IllegalStateException.class, () -> this.repository.extend(player,
                MemberKey.parse("test:mvp"), MemberScope.NETWORK, System.currentTimeMillis(),
                Duration.ofDays(30).toMillis(), List.of(vip), List.of(gift)));

        assertEquals(before, this.repository.load(player));
    }

    @Test
    void consecutiveUpgradesAndRenewalsShouldResumeEachSavedDurationAfterReload() {
        UUID player = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        MemberDefinition mvp = new MemberDefinition(MemberKey.parse("test:mvp"), MemberScope.NETWORK, "MVP", "", 2);
        MemberDefinition highest = new MemberDefinition(MemberKey.parse("test:highest"), MemberScope.NETWORK, "Highest", "", 3);
        Map<MemberKey, MemberDefinition> definitions = Map.of(vip.key(), vip, mvp.key(), mvp, highest.key(), highest);
        Instant start = Instant.ofEpochMilli(System.currentTimeMillis());
        long duration = Duration.ofDays(30).toMillis();
        this.repository.set(player, vip.key(), vip.scope(), start.plus(Duration.ofDays(10)));
        this.repository.extend(player, mvp.key(), mvp.scope(), start.toEpochMilli(), duration, List.of(vip));
        this.repository.extend(player, highest.key(), highest.scope(), start.plus(Duration.ofDays(5)).toEpochMilli(), duration,
                List.of(vip, mvp));
        this.repository.extend(player, highest.key(), highest.scope(), start.plus(Duration.ofDays(32)).toEpochMilli(), duration,
                List.of(vip, mvp));

        record Stage(int day, MemberKey expected) { }
        for (Stage stage : List.of(new Stage(32, highest.key()), new Stage(65, mvp.key()),
                new Stage(90, vip.key()), new Stage(100, null))) {
            MemberView reloaded = new MemberView(player, this.repository.load(player), definitions,
                    start.plus(Duration.ofDays(stage.day())));
            assertEquals(stage.expected(), reloaded.primary(Set.of(MemberScope.NETWORK))
                    .map(member -> member.record().key()).orElse(null));
        }
        List<MemberRecord> scheduled = MemberTimeline.resolve(this.repository.load(player));
        MemberRecord lower = scheduled.stream().filter(record -> record.key().equals(vip.key())).findFirst().orElseThrow();
        assertEquals(Duration.ofDays(10).toMillis(), lower.pause().remainingMillis());
        assertEquals(start.plus(Duration.ofDays(100)), lower.expiresAt());
    }

    @Test
    void revokingTheCoveringMembershipShouldResumeTheSavedDurationIncludingPermanentCoverage() {
        UUID player = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        MemberKey mvp = MemberKey.parse("test:mvp");
        Instant start = Instant.ofEpochMilli(System.currentTimeMillis());
        Duration saved = Duration.ofDays(10);
        this.repository.set(player, vip.key(), vip.scope(), start.plus(saved));
        this.repository.extend(player, mvp, MemberScope.NETWORK, start.toEpochMilli(), Duration.ofDays(30).toMillis(), List.of(vip));
        this.repository.set(player, mvp, MemberScope.NETWORK, null);
        MemberRecord paused = MemberTimeline.resolve(this.repository.load(player)).stream()
                .filter(record -> record.key().equals(vip.key())).findFirst().orElseThrow();
        assertFalse(paused.activeAt(start.plus(Duration.ofDays(36500))));
        Instant before = Instant.now();

        assertTrue(this.repository.revoke(player, mvp, MemberScope.NETWORK));

        Instant after = Instant.now();
        MemberRecord resumed = this.repository.load(player).getFirst();
        assertEquals(null, resumed.pause());
        assertTrue(!resumed.expiresAt().isBefore(before.plus(saved)) && !resumed.expiresAt().isAfter(after.plus(saved)));
        assertEquals(List.of(player), this.repository.activeMembers(vip.key(), vip.scope()).stream()
                .map(MemberRecord::playerUniqueId).toList());
    }

    @Test
    void replacementShouldNotResumeAnAlreadyExpiredLowerMembership() {
        UUID player = UUID.randomUUID();
        MemberDefinition vip = new MemberDefinition(MemberKey.parse("test:vip"), MemberScope.NETWORK, "VIP", "", 1);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        this.repository.set(player, vip.key(), vip.scope(), now.minusMillis(1));
        this.repository.extend(player, MemberKey.parse("test:mvp"), MemberScope.NETWORK, now.toEpochMilli(),
                Duration.ofDays(30).toMillis(), List.of(vip));
        MemberRecord expired = this.repository.load(player).stream().filter(record -> record.key().equals(vip.key())).findFirst().orElseThrow();
        assertEquals(null, expired.pause());
        assertFalse(expired.activeAt(now.plus(Duration.ofDays(30))));
    }

    @Test
    void revokeAllShouldRemoveEveryScopeAndPreserveOtherPlayersAndNames() {
        UUID playerId = UUID.randomUUID(), otherPlayer = UUID.randomUUID();
        MemberKey key = MemberKey.parse("test:vip");
        this.repository.set(playerId, key, MemberScope.NETWORK, null);
        this.repository.set(playerId, key, MemberScope.of("local"), Instant.now().plusSeconds(60));
        this.repository.set(playerId, MemberKey.parse("removed:member"), MemberScope.NETWORK, Instant.now().minusSeconds(60));
        this.repository.set(otherPlayer, key, MemberScope.NETWORK, null);
        this.repository.rememberName(playerId, "Player");

        assertTrue(this.repository.revokeAll(playerId));
        assertTrue(this.repository.load(playerId).isEmpty());
        assertEquals(1, this.repository.load(otherPlayer).size());
        assertEquals(playerId, this.repository.findPlayer("Player").orElseThrow());
        assertFalse(this.repository.revokeAll(playerId));
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
