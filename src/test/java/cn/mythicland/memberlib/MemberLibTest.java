package cn.mythicland.memberlib;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemberLibTest {
    @TempDir
    Path dataDirectory;

    @Test
    void hostShouldOwnRegistrationsAndPreserveRecordsAcrossLibraryRestarts() throws Exception {
        JavaPlugin host = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        ServicesManager services = mock(ServicesManager.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask refreshTask = mock(BukkitTask.class);
        PluginCommand command = mock(PluginCommand.class);
        CommandExecutor previousExecutor = mock(CommandExecutor.class);
        TabCompleter previousCompleter = mock(TabCompleter.class);
        Player onlinePlayer = mock(Player.class);
        UUID onlineId = UUID.randomUUID();
        when(onlinePlayer.getUniqueId()).thenReturn(onlineId);
        when(host.getCommand("member")).thenReturn(command);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.getServicesManager()).thenReturn(services);
        when(server.getScheduler()).thenReturn(scheduler);
        doReturn(List.of(onlinePlayer)).when(server).getOnlinePlayers();
        when(command.getExecutor()).thenReturn(previousExecutor);
        when(command.getTabCompleter()).thenReturn(previousCompleter);
        when(scheduler.runTaskTimer(eq(host), any(Runnable.class), eq(100L), eq(100L))).thenReturn(refreshTask);
        when(scheduler.runTask(eq(host), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return mock(BukkitTask.class);
        });
        UUID playerId = UUID.randomUUID();
        MemberDefinition definition = new MemberDefinition(
                MemberKey.of("test", "member"), MemberScope.NETWORK, "会员", "", 1
        );
        SQLiteDataSource hostDatabase = new SQLiteDataSource();
        hostDatabase.setUrl("jdbc:sqlite:" + this.dataDirectory.resolve("host.db"));
        DataSource dataSource = mock(DataSource.class, withSettings().extraInterfaces(AutoCloseable.class));
        when(dataSource.getConnection()).thenAnswer(ignored -> hostDatabase.getConnection());
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             InputStream schema = this.getClass().getResourceAsStream("/memberlib/db/sqlite/V1__membership.sql")) {
            for (String sql : new String(schema.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            statement.execute("CREATE TABLE host_data (value TEXT)");
            statement.execute("INSERT INTO host_data VALUES ('preserved')");
        }

        Field serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        Object previousServer = serverField.get(null);
        serverField.set(null, server);
        try {
            MemberLib library = new MemberLib(host);
            try (library) {
                library.service().register(definition);
                library.enable(dataSource, DatabaseMode.SQLITE);
                assertTrue(library.service().view(onlineId).records().isEmpty());
                assertTrue(library.service().makePermanent(playerId, definition.key()).join().permanent());
                verify(pluginManager).registerEvents(any(MemberPlayerListener.class), eq(host));
                verify(pluginManager).callEvent(any(MemberChangedEvent.class));
            }
            library.close();
            verify(refreshTask).cancel();
            verify(command).setExecutor(previousExecutor);
            verify(command).setTabCompleter(previousCompleter);
            verifyNoInteractions(services);

            try (MemberLib restarted = new MemberLib(host)) {
                restarted.service().register(definition);
                restarted.enable(dataSource, DatabaseMode.SQLITE);
                assertTrue(restarted.service().load(playerId).join().active(definition.key()));
            }
            verify((AutoCloseable) dataSource, never()).close();
            verify(host, never()).getDataFolder();
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT value FROM host_data")) {
                assertTrue(result.next());
                assertTrue("preserved".equals(result.getString(1)));
            }
        } finally {
            serverField.set(null, previousServer);
        }
    }
}
