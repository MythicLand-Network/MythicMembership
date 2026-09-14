package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.command.BukkitCommandRegistrar;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.framework.component.ComponentResolver;
import cn.mythicland.mythicmembership.api.MembershipApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

public final class MembershipRuntime {
    private static final long REFRESH_TICKS = 100L;

    private final MythicMembershipPlugin plugin;
    private DatabaseManager databaseManager;
    private MembershipService membershipService;

    public MembershipRuntime(MythicMembershipPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        MembershipSettings settings = MembershipSettings.read(this.plugin);
        this.databaseManager = new DatabaseManager(settings);
        this.membershipService = new MembershipService(
                this.plugin,
                new MembershipRepository(this.databaseManager.dataSource(), settings.databaseMode())
        );

        Bukkit.getServicesManager().register(
                MembershipApi.class,
                this.membershipService,
                this.plugin,
                ServicePriority.Normal
        );
    }

    public void enable() {
        Bukkit.getPluginManager().registerEvents(
                new MembershipPlayerListener(this.membershipService),
                this.plugin
        );
        this.registerCommands();

        Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) this.membershipService.refresh(player.getUniqueId());
        }, REFRESH_TICKS, REFRESH_TICKS);
        for (Player player : Bukkit.getOnlinePlayers()) this.membershipService.load(player.getUniqueId());
    }

    public void reload() {
    }

    public void unload() {
        Bukkit.getServicesManager().unregisterAll(this.plugin);
        Bukkit.getScheduler().cancelTasks(this.plugin);
        if (this.membershipService != null) this.membershipService.close();
        if (this.databaseManager != null) this.databaseManager.close();
        this.membershipService = null;
        this.databaseManager = null;
    }

    private void registerCommands() {
        ComponentContainer components = new ComponentContainer();
        components.bind(ComponentResolver.class, components);
        components.bind(new MembershipAdminCommand(this.plugin, this.membershipService));
        BukkitCommandRegistrar.register(this.plugin, components);
    }
}
