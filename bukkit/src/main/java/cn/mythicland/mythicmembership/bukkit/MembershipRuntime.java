package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.bukkit.BukkitComponentRegistrar;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.mythicmembership.api.MembershipApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

public final class MembershipRuntime {
    private static final long REFRESH_TICKS = 100L;

    private final MythicMembershipPlugin plugin;
    private ComponentContainer components;
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

        this.components = new ComponentContainer();
        this.components.bind(this.plugin);
        this.components.bind(settings);
        this.components.bind(this.databaseManager);
        this.components.bind(this.membershipService);
        this.components.bind(this.components);
        this.components.scan("cn.mythicland.mythicmembership.bukkit");

        Bukkit.getServicesManager().register(
                MembershipApi.class,
                this.membershipService,
                this.plugin,
                ServicePriority.Normal
        );
    }

    public void enable() {
        BukkitComponentRegistrar.register(this.plugin, this.components);

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
        if (this.components != null) this.components.unload();
        if (this.membershipService != null) this.membershipService.close();
        if (this.databaseManager != null) this.databaseManager.close();
        this.membershipService = null;
        this.databaseManager = null;
        this.components = null;
    }
}
