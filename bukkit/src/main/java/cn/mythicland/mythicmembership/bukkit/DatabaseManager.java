package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.database.DatabaseConfiguration;

import javax.sql.DataSource;
import java.util.List;
import java.util.logging.Logger;

final class DatabaseManager implements AutoCloseable {
    private final cn.mythicland.framework.database.DatabaseManager frameworkDatabase;

    DatabaseManager(MembershipSettings settings) {
        cn.mythicland.framework.database.DatabaseMode mode = settings.databaseMode() == DatabaseMode.SQLITE
                ? cn.mythicland.framework.database.DatabaseMode.SQLITE
                : cn.mythicland.framework.database.DatabaseMode.POSTGRESQL;
        DatabaseConfiguration configuration = DatabaseConfiguration.builder(mode, settings.jdbcUrl())
                .sqliteFile(settings.sqliteFile())
                .credentials(settings.username(), settings.password())
                .maximumPoolSize(settings.databaseMode() == DatabaseMode.SQLITE ? 1 : 10)
                .minimumIdle(settings.databaseMode() == DatabaseMode.SQLITE ? 1 : 10)
                .poolName("MythicMembership")
                .migrationLocations(List.of("classpath:db/" + settings.databaseMode().name().toLowerCase()))
                .build();
        this.frameworkDatabase = new cn.mythicland.framework.database.DatabaseManager(
                configuration,
                Logger.getLogger("MythicMembership")
        );
        this.frameworkDatabase.start();
    }

    DataSource dataSource() {
        return this.frameworkDatabase.dataSource();
    }

    @Override
    public void close() {
        this.frameworkDatabase.close();
    }
}
