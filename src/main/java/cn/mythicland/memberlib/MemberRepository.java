package cn.mythicland.memberlib;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class MemberRepository {
    private static final String UPSERT = """
            INSERT INTO mythic_membership (player_uuid, membership_id, scope, expires_at_millis, paused_by, paused_remaining_millis)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (player_uuid, membership_id, scope) DO UPDATE SET
                expires_at_millis = excluded.expires_at_millis,
                paused_by = excluded.paused_by, paused_remaining_millis = excluded.paused_remaining_millis
            """;

    private final DataSource dataSource;
    private final DatabaseMode mode;

    MemberRepository(DataSource dataSource, DatabaseMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    List<MemberRecord> load(UUID playerUniqueId) {
        try (Connection connection = this.dataSource.getConnection()) {
            return this.load(connection, playerUniqueId);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load memberships for " + playerUniqueId, exception);
        }
    }

    private List<MemberRecord> load(Connection connection, UUID playerUniqueId) throws SQLException {
        String sql = "SELECT membership_id, scope, expires_at_millis, paused_by, paused_remaining_millis "
                + "FROM mythic_membership WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUniqueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<MemberRecord> records = new ArrayList<>();
                while (result.next()) {
                    long expiry = result.getLong("expires_at_millis");
                    boolean permanent = result.wasNull();
                    String blocker = result.getString("paused_by");
                    records.add(new MemberRecord(
                            playerUniqueId,
                            MemberKey.parse(result.getString("membership_id")),
                            MemberScope.of(result.getString("scope")),
                            permanent ? null : Instant.ofEpochMilli(expiry),
                            blocker == null ? null : new MemberRecord.Pause(MemberKey.parse(blocker),
                                    result.getLong("paused_remaining_millis"), null)
                    ));
                }
                return List.copyOf(records);
            }
        }
    }

    MemberRecord set(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope,
            Instant expiry
    ) {
        this.upsert(playerUniqueId, key, scope, expiry);
        return new MemberRecord(playerUniqueId, key, scope, expiry);
    }

    MemberRecord extend(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope,
            long nowMillis,
            long durationMillis
    ) {
        return this.extend(playerUniqueId, key, scope, nowMillis, durationMillis, List.of());
    }

    MemberRecord extend(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope,
            long nowMillis,
            long durationMillis,
            List<MemberDefinition> replaced
    ) {
        return this.extend(playerUniqueId, key, scope, nowMillis, durationMillis, replaced, List.of());
    }

    MemberRecord extend(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope,
            long nowMillis,
            long durationMillis,
            List<MemberDefinition> replaced,
            List<MemberDefinition> granted
    ) {
        String maximum = this.mode == DatabaseMode.POSTGRESQL ? "GREATEST" : "MAX";
        String sql = "INSERT INTO mythic_membership (player_uuid, membership_id, scope, expires_at_millis) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (player_uuid, membership_id, scope) "
                + "DO UPDATE SET expires_at_millis = "
                + "CASE WHEN mythic_membership.expires_at_millis IS NULL THEN NULL ELSE " + maximum
                + "(mythic_membership.expires_at_millis, ?) + ? END, paused_by = NULL, paused_remaining_millis = NULL";
        long initialExpiry = Math.addExact(nowMillis, durationMillis);
        MemberRecord committed = null;
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant now = Instant.ofEpochMilli(nowMillis);
                List<MemberRecord> previous = MemberTimeline.resolve(this.load(connection, playerUniqueId));
                MemberRecord existing = previous.stream().filter(record -> record.key().equals(key)).findFirst().orElse(null);
                if (existing != null && existing.permanent()) {
                    throw new IllegalStateException("Permanent membership cannot be extended: " + key);
                }
                boolean paused = existing != null && existing.pause() != null
                        && (existing.pause().resumesAt() == null || now.isBefore(existing.pause().resumesAt()));
                if (paused) {
                    this.upsert(connection, new MemberRecord(playerUniqueId, key, scope, existing.expiresAt(),
                            new MemberRecord.Pause(existing.pause().blocker(),
                                    Math.addExact(existing.pause().remainingMillis(), durationMillis), null)));
                } else {
                    if (existing != null && existing.pause() != null) {
                        this.upsert(connection, new MemberRecord(playerUniqueId, key, scope, existing.expiresAt()));
                    }
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setString(1, playerUniqueId.toString());
                        statement.setString(2, key.value());
                        statement.setString(3, scope.value());
                        statement.setLong(4, initialExpiry);
                        statement.setLong(5, nowMillis);
                        statement.setLong(6, durationMillis);
                        statement.executeUpdate();
                    }
                }
                MemberKey blocker = key;
                for (MemberDefinition definition : replaced.stream()
                        .sorted(Comparator.comparingInt(MemberDefinition::priority).reversed()).toList()) {
                    MemberRecord covered = previous.stream().filter(record -> record.key().equals(definition.key())).findFirst().orElse(null);
                    if (covered == null) continue;
                    long remaining = covered.permanent() ? 0L : covered.remainingMillisAt(now);
                    if (!covered.permanent() && remaining == 0L) continue;
                    this.upsert(connection, new MemberRecord(playerUniqueId, covered.key(), covered.scope(), covered.expiresAt(),
                            new MemberRecord.Pause(blocker, remaining, null)));
                    blocker = covered.key();
                }
                for (MemberDefinition definition : granted) {
                    MemberRecord gift = previous.stream().filter(record -> record.key().equals(definition.key())).findFirst().orElse(null);
                    if (gift != null && gift.permanent()) continue;
                    if (gift != null && gift.pause() != null
                            && (gift.pause().resumesAt() == null || now.isBefore(gift.pause().resumesAt()))) {
                        this.upsert(connection, new MemberRecord(playerUniqueId, gift.key(), gift.scope(), gift.expiresAt(),
                                new MemberRecord.Pause(gift.pause().blocker(),
                                        Math.addExact(gift.pause().remainingMillis(), durationMillis), null)));
                    } else {
                        long expiry = Math.addExact(gift == null ? nowMillis
                                : Math.max(nowMillis, gift.expiresAt().toEpochMilli()), durationMillis);
                        this.upsert(connection, new MemberRecord(playerUniqueId, definition.key(), definition.scope(),
                                Instant.ofEpochMilli(expiry)));
                    }
                }
                MemberRecord result = MemberTimeline.resolve(this.load(connection, playerUniqueId)).stream()
                        .filter(record -> record.key().equals(key)).findFirst().orElseThrow();
                try {
                    connection.commit();
                } catch (SQLException exception) {
                    throw new MemberCommitException("Membership commit outcome is unknown: " + key, exception);
                }
                committed = result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            if (committed == null) {
                throw new IllegalStateException("Failed to extend membership " + key + " for " + playerUniqueId, exception);
            }
            // A connection close failure cannot undo an acknowledged commit.
        }
        return committed;
    }

    List<MemberRecord> activeMembers(MemberKey key, MemberScope scope) {
        String sql = "SELECT player_uuid, expires_at_millis, paused_by, paused_remaining_millis FROM mythic_membership "
                + "WHERE membership_id = ? AND scope = ? AND (paused_by IS NOT NULL OR expires_at_millis IS NULL OR expires_at_millis > ?)";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.value());
            statement.setString(2, scope.value());
            Instant now = Instant.now();
            statement.setLong(3, now.toEpochMilli());
            List<MemberRecord> candidates = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long expiry = rows.getLong("expires_at_millis");
                    boolean permanent = rows.wasNull();
                    String blocker = rows.getString("paused_by");
                    candidates.add(new MemberRecord(UUID.fromString(rows.getString("player_uuid")),
                            key, scope, permanent ? null : Instant.ofEpochMilli(expiry),
                            blocker == null ? null : new MemberRecord.Pause(MemberKey.parse(blocker),
                                    rows.getLong("paused_remaining_millis"), null)));
                }
            }
            List<MemberRecord> records = new ArrayList<>();
            for (MemberRecord candidate : candidates) {
                MemberRecord record = candidate.pause() == null ? candidate
                        : MemberTimeline.resolve(this.load(connection, candidate.playerUniqueId())).stream()
                        .filter(value -> value.key().equals(key) && value.scope().equals(scope)).findFirst().orElseThrow();
                if (record.activeAt(now)) records.add(record);
            }
            return List.copyOf(records);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load active memberships: " + key, exception);
        }
    }

    boolean revoke(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope
    ) {
        String sql = "DELETE FROM mythic_membership WHERE player_uuid = ? AND membership_id = ? AND scope = ?";
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                List<MemberRecord> records = MemberTimeline.resolve(this.load(connection, playerUniqueId));
                MemberRecord removed = records.stream().filter(record -> record.key().equals(key) && record.scope().equals(scope))
                        .findFirst().orElse(null);
                if (removed == null) return false;
                Instant now = Instant.now();
                for (MemberRecord record : records) {
                    if (record.pause() == null || !record.pause().blocker().equals(key)) continue;
                    boolean stillCovered = removed.pause() != null
                            && (removed.pause().resumesAt() == null || now.isBefore(removed.pause().resumesAt()));
                    MemberRecord updated;
                    if (stillCovered) {
                        updated = new MemberRecord(playerUniqueId, record.key(), record.scope(), record.expiresAt(),
                                new MemberRecord.Pause(removed.pause().blocker(), record.pause().remainingMillis(), null));
                    } else {
                        Instant start = !removed.permanent() && removed.expiresAt().isBefore(now) ? removed.expiresAt() : now;
                        updated = new MemberRecord(playerUniqueId, record.key(), record.scope(),
                                record.permanent() ? null : start.plusMillis(record.pause().remainingMillis()));
                    }
                    this.upsert(connection, updated);
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerUniqueId.toString());
                    statement.setString(2, key.value());
                    statement.setString(3, scope.value());
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to revoke membership " + key + " for " + playerUniqueId, exception);
        }
    }

    boolean revokeAll(UUID playerUniqueId) {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM mythic_membership WHERE player_uuid = ?")) {
            statement.setString(1, playerUniqueId.toString());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to revoke all memberships for " + playerUniqueId, exception);
        }
    }

    void rememberName(UUID playerUniqueId, String playerName) {
        String sql = "INSERT INTO mythic_membership_player_name "
                + "(player_name_lower, player_name, player_uuid, last_seen_millis) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (player_name_lower) DO UPDATE SET "
                + "player_name = excluded.player_name, "
                + "player_uuid = excluded.player_uuid, last_seen_millis = excluded.last_seen_millis";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerName.toLowerCase(Locale.ROOT));
            statement.setString(2, playerName);
            statement.setString(3, playerUniqueId.toString());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to remember player name " + playerName, exception);
        }
    }

    Optional<UUID> findPlayer(String input) {
        try {
            return Optional.of(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
        }
        String sql = "SELECT player_uuid FROM mythic_membership_player_name WHERE player_name_lower = ?";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, input.toLowerCase(Locale.ROOT));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to resolve player " + input, exception);
        }
    }

    private void upsert(
            UUID playerUniqueId,
            MemberKey key,
            MemberScope scope,
            Instant expiry
    ) {
        try (Connection connection = this.dataSource.getConnection()) {
            this.upsert(connection, new MemberRecord(playerUniqueId, key, scope, expiry));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to set membership " + key + " for " + playerUniqueId, exception);
        }
    }

    private void upsert(Connection connection, MemberRecord record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, record.playerUniqueId().toString());
            statement.setString(2, record.key().value());
            statement.setString(3, record.scope().value());
            if (record.permanent()) statement.setNull(4, java.sql.Types.BIGINT);
            else statement.setLong(4, record.expiresAt().toEpochMilli());
            if (record.pause() == null) {
                statement.setNull(5, java.sql.Types.VARCHAR);
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setString(5, record.pause().blocker().value());
                statement.setLong(6, record.pause().remainingMillis());
            }
            statement.executeUpdate();
        }
    }
}
