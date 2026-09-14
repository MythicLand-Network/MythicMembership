package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.mythicmembership.bukkit.bootstrap.RuntimeBootstrap;
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
        this.runtimeBootstrap = new RuntimeBootstrap(
                this,
                "cn.mythicland.mythicmembership.bukkit.MembershipRuntime",
                this.getDataFolder().toPath(),
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
