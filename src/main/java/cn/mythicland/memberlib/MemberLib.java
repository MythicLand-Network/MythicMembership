package cn.mythicland.memberlib;

import cn.mythicland.framework.bukkit.BukkitComponentRegistrar;
import cn.mythicland.framework.bukkit.BukkitRegistration;
import cn.mythicland.framework.bukkit.task.BukkitTaskScheduler;
import cn.mythicland.framework.bukkit.task.ScheduledTaskLifecycle;
import cn.mythicland.framework.bukkit.task.TaskScheduler;
import cn.mythicland.framework.component.ComponentContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.util.Objects;

public final class MemberLib implements AutoCloseable {
    private final JavaPlugin plugin;
    private ComponentContainer components;
    private BukkitRegistration registration;
    private MemberService memberService;
    private ScheduledTaskLifecycle scheduledTaskLifecycle;

    public MemberLib(JavaPlugin plugin) {
        this.plugin = plugin;
        try {
            this.memberService = new MemberService(this.plugin);

            this.components = new ComponentContainer();
            this.components.bind(JavaPlugin.class, this.plugin);
            this.components.bind(this.memberService);
            this.components.bind(this.components);
            TaskScheduler taskScheduler = new BukkitTaskScheduler(this.plugin);
            this.components.bind(TaskScheduler.class, taskScheduler);
            this.scheduledTaskLifecycle = new ScheduledTaskLifecycle(this.components, taskScheduler);
            this.components.bind(this.scheduledTaskLifecycle);
            this.components.scan("cn.mythicland.memberlib");
        } catch (RuntimeException | Error exception) {
            try {
                this.close();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    public MemberService service() {
        return this.memberService;
    }

    public void enable(DataSource dataSource, DatabaseMode mode) {
        this.memberService.connect(new MemberRepository(
                Objects.requireNonNull(dataSource, "dataSource"),
                Objects.requireNonNull(mode, "mode")
        ));
        this.registration = BukkitComponentRegistrar.register(this.plugin, this.components);
        this.scheduledTaskLifecycle.start();
        for (Player player : Bukkit.getOnlinePlayers()) this.memberService.load(player.getUniqueId()).join();
    }

    @Override
    public void close() {
        Throwable failure = null;
        try {
            if (this.registration != null) this.registration.close();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            if (this.components != null) this.components.unload();
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            if (this.memberService != null) this.memberService.close();
        } catch (RuntimeException | Error exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        } finally {
            this.registration = null;
            this.memberService = null;
            this.scheduledTaskLifecycle = null;
            this.components = null;
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException exception) throw exception;
    }
}
