package cn.mythicland.mythicmembership.bukkit;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.file.Path;
import java.util.Locale;

record MembershipSettings(
        DatabaseMode databaseMode,
        Path sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password
) {
    static MembershipSettings read(MythicMembershipPlugin plugin) {
        FileConfiguration config = plugin.getConfig();
        DatabaseMode mode = switch (config.getString("database.mode", "sqlite").trim().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> DatabaseMode.SQLITE;
            case "postgres", "postgresql" -> DatabaseMode.POSTGRESQL;
            default -> throw new IllegalArgumentException("Unsupported membership database mode");
        };
        String host = mode == DatabaseMode.POSTGRESQL
                ? required(config.getString("database.postgresql.host"), "PostgreSQL host")
                : "";
        String database = mode == DatabaseMode.POSTGRESQL
                ? required(config.getString("database.postgresql.database"), "PostgreSQL database")
                : "";
        String username = mode == DatabaseMode.POSTGRESQL
                ? required(config.getString("database.postgresql.username"), "PostgreSQL username")
                : "";
        return new MembershipSettings(
                mode,
                plugin.getDataFolder().toPath().resolve(config.getString("database.sqlite-file", "membership.db")),
                host,
                config.getInt("database.postgresql.port", 5432),
                database,
                username,
                config.getString("database.postgresql.password", "")
        );
    }

    String jdbcUrl() {
        if (this.databaseMode == DatabaseMode.SQLITE) {
            return "jdbc:sqlite:" + this.sqliteFile.toAbsolutePath().normalize();
        }
        return "jdbc:postgresql://" + this.host + ":" + this.port + "/" + this.database;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " cannot be blank");
        return value.trim();
    }
}
