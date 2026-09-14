package cn.mythicland.mythicmembership.bukkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sqliteShouldMigrateAndOpenRepository() {
        MembershipSettings settings = new MembershipSettings(
                DatabaseMode.SQLITE,
                this.temporaryDirectory.resolve("membership.db"),
                "",
                5432,
                "",
                "",
                ""
        );

        try (DatabaseManager database = new DatabaseManager(settings)) {
            MembershipRepository repository = new MembershipRepository(database.dataSource(), DatabaseMode.SQLITE);
            assertTrue(repository.load(UUID.randomUUID()).isEmpty());
        }
    }
}
