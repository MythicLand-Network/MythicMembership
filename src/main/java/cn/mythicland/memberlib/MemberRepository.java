package cn.mythicland.memberlib;


import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class MemberRepository {
    private static final String UPSERT = """
            INSERT INTO mythic_membership (player_uuid, membership_id, scope, expires_at_millis)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid, membership_id, scope) DO UPDATE SET expires_at_millis = excluded.expires_at_millis
            """;

    private final DataSource dataSource;
    private final DatabaseMode mode;

    MemberRepository(DataSource dataSource, DatabaseMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    List<MemberRecord> load(UUID playerUniqueId) {
        String sql = "SELECT membership_id, scope, expires_at_millis FROM mythic_membership WHERE player_uuid = ?";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUniqueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<MemberRecord> records = new ArrayList<>();
                while (result.next()) {
                    long expiry = result.getLong("expires_at_millis");
                    boolean permanent = result.wasNull();
                    records.add(new MemberRecord(
                            playerUniqueId,
                            MemberKey.parse(result.getString("membership_id")),
                            MemberScope.of(result.getString("scope")),
                            permanent ? null : Instant.ofEpochMilli(expiry)
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load memberships for " + playerUniqueId, exception);
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
        String maximum = this.mode == DatabaseMode.POSTGRESQL ? "GREATEST" : "MAX";
        String sql = "INSERT INTO mythic_membership (player_uuid, membership_id, scope, expires_at_millis) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (player_uuid, membership_id, scope) "
                + "DO UPDATE SET expires_at_millis = "
                + "CASE WHEN mythic_membership.expires_at_millis IS NULL THEN NULL ELSE " + maximum
                + "(mythic_membership.expires_at_millis, ?) + ? END";
        long initialExpiry = Math.addExact(nowMillis, durationMillis);
        MemberRecord committed = null;
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, playerUniqueId.toString());
                    statement.setString(2, key.value());
                    statement.setString(3, scope.value());
                    statement.setLong(4, initialExpiry);
                    statement.setLong(5, nowMillis);
                    statement.setLong(6, durationMillis);
                    statement.executeUpdate();
                }
                MemberRecord result;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT expires_at_millis FROM mythic_membership "
                                + "WHERE player_uuid = ? AND membership_id = ? AND scope = ?"
                )) {
                    statement.setString(1, playerUniqueId.toString());
                    statement.setString(2, key.value());
                    statement.setString(3, scope.value());
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw new IllegalStateException("Extended membership was not found: " + key);
                        long expiry = rows.getLong(1);
                        if (rows.wasNull()) throw new IllegalStateException("Permanent membership cannot be extended: " + key);
                        result = new MemberRecord(playerUniqueId, key, scope, Instant.ofEpochMilli(expiry));
                    }
                }
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
        String sql = "SELECT player_uuid, expires_at_millis FROM mythic_membership "
                + "WHERE membership_id = ? AND scope = ? AND (expires_at_millis IS NULL OR expires_at_millis > ?)";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key.value());
            statement.setString(2, scope.value());
            statement.setLong(3, System.currentTimeMillis());
            try (ResultSet rows = statement.executeQuery()) {
                List<MemberRecord> records = new ArrayList<>();
                while (rows.next()) {
                    long expiry = rows.getLong("expires_at_millis");
                    boolean permanent = rows.wasNull();
                    records.add(new MemberRecord(UUID.fromString(rows.getString("player_uuid")),
                            key, scope, permanent ? null : Instant.ofEpochMilli(expiry)));
                }
                return List.copyOf(records);
            }
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
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUniqueId.toString());
            statement.setString(2, key.value());
            statement.setString(3, scope.value());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to revoke membership " + key + " for " + playerUniqueId, exception);
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
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, playerUniqueId.toString());
            statement.setString(2, key.value());
            statement.setString(3, scope.value());
            if (expiry == null) statement.setNull(4, java.sql.Types.BIGINT);
            else statement.setLong(4, expiry.toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to set membership " + key + " for " + playerUniqueId, exception);
        }
    }
}
