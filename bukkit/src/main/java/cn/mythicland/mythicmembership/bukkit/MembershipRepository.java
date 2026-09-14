package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.mythicmembership.api.MembershipKey;
import cn.mythicland.mythicmembership.api.MembershipRecord;
import cn.mythicland.mythicmembership.api.MembershipScope;

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

final class MembershipRepository {
    private static final String UPSERT = """
            INSERT INTO mythic_membership (player_uuid, membership_id, scope, expires_at_millis)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (player_uuid, membership_id, scope) DO UPDATE SET expires_at_millis = excluded.expires_at_millis
            """;

    private final DataSource dataSource;
    private final DatabaseMode mode;

    MembershipRepository(DataSource dataSource, DatabaseMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    List<MembershipRecord> load(UUID playerUniqueId) {
        String sql = "SELECT membership_id, scope, expires_at_millis FROM mythic_membership WHERE player_uuid = ?";
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUniqueId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<MembershipRecord> records = new ArrayList<>();
                while (result.next()) {
                    long expiry = result.getLong("expires_at_millis");
                    boolean permanent = result.wasNull();
                    records.add(new MembershipRecord(
                            playerUniqueId,
                            MembershipKey.parse(result.getString("membership_id")),
                            MembershipScope.of(result.getString("scope")),
                            permanent ? null : Instant.ofEpochMilli(expiry)
                    ));
                }
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load memberships for " + playerUniqueId, exception);
        }
    }

    MembershipRecord set(
            UUID playerUniqueId,
            MembershipKey key,
            MembershipScope scope,
            Instant expiry
    ) {
        this.upsert(playerUniqueId, key, scope, expiry);
        return new MembershipRecord(playerUniqueId, key, scope, expiry);
    }

    MembershipRecord extend(
            UUID playerUniqueId,
            MembershipKey key,
            MembershipScope scope,
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
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUniqueId.toString());
            statement.setString(2, key.value());
            statement.setString(3, scope.value());
            statement.setLong(4, initialExpiry);
            statement.setLong(5, nowMillis);
            statement.setLong(6, durationMillis);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to extend membership " + key + " for " + playerUniqueId, exception);
        }
        MembershipRecord result = this.load(playerUniqueId).stream()
                .filter(record -> record.key().equals(key) && record.scope().equals(scope))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Extended membership was not found: " + key));
        if (result.permanent()) throw new IllegalStateException("Permanent membership cannot be extended: " + key);
        return result;
    }

    boolean revoke(
            UUID playerUniqueId,
            MembershipKey key,
            MembershipScope scope
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
            MembershipKey key,
            MembershipScope scope,
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
