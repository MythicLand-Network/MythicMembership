package cn.mythicland.mythicmembership.bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import javax.annotation.Nonnull;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;

final class DatabaseManager implements AutoCloseable {
    private final HikariDataSource dataSource;

    DatabaseManager(MembershipSettings settings) {
        if (settings.databaseMode() == DatabaseMode.SQLITE) {
            try {
                Files.createDirectories(settings.sqliteFile().toAbsolutePath().normalize().getParent());
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to create membership database directory", exception);
            }
        }
        HikariDataSource candidate = getHikariDataSource(settings);
        try {
            Flyway.configure()
                    .dataSource(candidate)
                    .locations("classpath:db/" + settings.databaseMode().name().toLowerCase())
                    .load()
                    .migrate();
        } catch (RuntimeException exception) {
            candidate.close();
            throw new IllegalStateException("Failed to migrate membership database", exception);
        }
        this.dataSource = candidate;
    }

    @Nonnull
    private static HikariDataSource getHikariDataSource(MembershipSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setMaximumPoolSize(settings.databaseMode() == DatabaseMode.SQLITE ? 1 : 10);
        config.setPoolName("MythicMembership");
        if (settings.databaseMode() == DatabaseMode.SQLITE) config.setConnectionInitSql("PRAGMA foreign_keys = ON");
        else {
            config.setUsername(settings.username());
            config.setPassword(settings.password());
        }
        HikariDataSource candidate = new HikariDataSource(config);
        return candidate;
    }

    DataSource dataSource() {
        return this.dataSource;
    }

    @Override
    public void close() {
        this.dataSource.close();
    }
}
