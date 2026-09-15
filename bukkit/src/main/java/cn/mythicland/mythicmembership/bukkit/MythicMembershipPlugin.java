package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.runtime.RuntimeBootstrap;
import cn.mythicland.mythicmembership.bukkit.bootstrap.RuntimeBootstrapFactory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Set;

public final class MythicMembershipPlugin extends JavaPlugin {
    private RuntimeBootstrap runtimeBootstrap;

    @Override
    public void onLoad() {
        this.saveDefaultConfig();
        String databaseMode = this.getConfig().getString("database.mode", "sqlite")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (databaseMode.equals("postgres")) databaseMode = "postgresql";
        this.runtimeBootstrap = RuntimeBootstrapFactory.create(
                this,
                Set.of("common", databaseMode)
        );
        this.runtimeBootstrap.load();
    }

    @Override
    public void onEnable() {
        this.runtimeBootstrap.enable();
    }

    @Override
    public void onDisable() {
        if (this.runtimeBootstrap == null) return;
        this.runtimeBootstrap.disable();
        this.runtimeBootstrap = null;
    }
}
