package cn.mythicland.mythicmembership.bukkit.bootstrap;

import cn.mythicland.framework.runtime.FrameworkRuntimeClassLoading;
import cn.mythicland.framework.runtime.RuntimeBootstrap;
import cn.mythicland.mythicmembership.bukkit.MythicMembershipPlugin;

import java.util.List;
import java.util.Set;

public final class RuntimeBootstrapFactory {
    private static final String RUNTIME_CLASS =
            "cn.mythicland.mythicmembership.bukkit.MembershipRuntime";

    private RuntimeBootstrapFactory() {
    }

    public static RuntimeBootstrap create(MythicMembershipPlugin plugin, Set<String> activePlatforms) {
        return RuntimeBootstrap.builder(plugin, RUNTIME_CLASS)
                .runtimeName("MythicMembership")
                .libraries(
                        plugin.getDataFolder().toPath(),
                        "META-INF/mythicmembership-libraries.properties",
                        activePlatforms
                )
                .loggers(plugin.getLogger()::info, plugin.getLogger()::warning)
                .classLoading(FrameworkRuntimeClassLoading.bukkit(
                        List.of(
                                "cn.mythicland.mythicmembership.bukkit.",
                                "com.zaxxer.hikari.",
                                "org.flywaydb.",
                                "org.postgresql.",
                                "org.sqlite.",
                                "com.fasterxml.jackson.",
                                "tools.jackson."
                        ),
                        List.of(MythicMembershipPlugin.class.getName()),
                        List.of("cn.mythicland.mythicmembership.bukkit.bootstrap.")
                ))
                .build();
    }
}
