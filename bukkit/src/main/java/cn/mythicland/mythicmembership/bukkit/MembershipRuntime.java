package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.bukkit.BukkitComponentRegistrar;
import cn.mythicland.framework.bukkit.BukkitRegistration;
import cn.mythicland.framework.bukkit.task.BukkitTaskScheduler;
import cn.mythicland.framework.bukkit.task.ScheduledTaskLifecycle;
import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentContainer;
import cn.mythicland.mythicmembership.api.MembershipApi;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

public final class MembershipRuntime {
    private final MythicMembershipPlugin plugin;
    private ComponentContainer components;
    private BukkitRegistration registration;
    private DatabaseManager databaseManager;
    private MembershipService membershipService;
    private ScheduledTaskLifecycle scheduledTaskLifecycle;

    public MembershipRuntime(MythicMembershipPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try {
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
            TaskScheduler taskScheduler = new BukkitTaskScheduler(this.plugin);
            this.components.bind(TaskScheduler.class, taskScheduler);
            this.scheduledTaskLifecycle = new ScheduledTaskLifecycle(this.components, taskScheduler);
            this.components.bind(this.scheduledTaskLifecycle);
            this.components.scan("cn.mythicland.mythicmembership.bukkit");

            Bukkit.getServicesManager().register(
                    MembershipApi.class,
                    this.membershipService,
                    this.plugin,
                    ServicePriority.Normal
            );
        } catch (RuntimeException | Error exception) {
            try {
                this.unload();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public void enable() {
        this.registration = BukkitComponentRegistrar.register(this.plugin, this.components);
        this.scheduledTaskLifecycle.start();
        for (Player player : Bukkit.getOnlinePlayers()) this.membershipService.load(player.getUniqueId());
    }

    public void reload() {
    }

    public void unload() {
        Throwable failure = null;
        try {
            if (this.registration != null) this.registration.close();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            Bukkit.getServicesManager().unregisterAll(this.plugin);
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            if (this.components != null) this.components.unload();
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            if (this.membershipService != null) this.membershipService.close();
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            if (this.databaseManager != null) this.databaseManager.close();
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            this.registration = null;
            this.membershipService = null;
            this.databaseManager = null;
            this.scheduledTaskLifecycle = null;
            this.components = null;
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }
}
