package cn.mythicland.mythicmembership.bukkit.bootstrap;

import cn.mythicland.framework.runtime.RuntimeLibraryLoader;
import cn.mythicland.framework.runtime.SelectiveChildFirstClassLoader;
import cn.mythicland.mythicmembership.bukkit.MythicMembershipPlugin;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RuntimeBootstrap {
    private final MythicMembershipPlugin plugin;
    private final String runtimeClassName;
    private final Path dataDirectory;
    private final Set<String> activePlatforms;
    private SelectiveChildFirstClassLoader classLoader;
    private Object runtime;
    private Method loadMethod;
    private Method enableMethod;
    private Method unloadMethod;

    public RuntimeBootstrap(
            MythicMembershipPlugin plugin,
            String runtimeClassName,
            Path dataDirectory,
            Set<String> activePlatforms
    ) {
        this.plugin = plugin;
        this.runtimeClassName = runtimeClassName;
        this.dataDirectory = dataDirectory;
        this.activePlatforms = Set.copyOf(activePlatforms);
    }

    public void load() {
        try {
            List<URL> urls = new ArrayList<>();
            urls.add(this.pluginJarUrl());
            urls.addAll(new RuntimeLibraryLoader(
                    this.dataDirectory,
                    this.plugin.getClass().getClassLoader(),
                    "META-INF/mythicmembership-libraries.properties",
                    this.plugin.getLogger()::info,
                    this.plugin.getLogger()::warning
            ).resolveLibraries(this.activePlatforms));
            this.classLoader = new SelectiveChildFirstClassLoader(
                    urls.toArray(URL[]::new),
                    this.plugin.getClass().getClassLoader(),
                    List.of(
                            "cn.mythicland.mythicmembership.bukkit.",
                            "cn.mythicland.framework.",
                            "com.zaxxer.hikari.",
                            "org.flywaydb.",
                            "org.postgresql.",
                            "org.sqlite.",
                            "com.fasterxml.jackson.",
                            "tools.jackson."
                    ),
                    List.of("cn.mythicland.mythicmembership.bukkit.MythicMembershipPlugin"),
                    List.of("cn.mythicland.mythicmembership.bukkit.bootstrap.")
            );
            Class<?> runtimeType = Class.forName(this.runtimeClassName, true, this.classLoader);
            this.runtime = runtimeType.getConstructor(MythicMembershipPlugin.class).newInstance(this.plugin);
            this.loadMethod = runtimeType.getMethod("load");
            this.enableMethod = runtimeType.getMethod("enable");
            this.unloadMethod = runtimeType.getMethod("unload");
            this.invoke(this.loadMethod);
        } catch (ReflectiveOperationException exception) {
            this.closeAfterFailure(exception);
            throw new IllegalStateException("Failed to load MythicMembership runtime", exception);
        }
    }

    public void enable() {
        this.invoke(this.enableMethod);
    }

    public void disable() {
        try {
            if (this.runtime != null) this.invoke(this.unloadMethod);
        } finally {
            this.runtime = null;
            this.loadMethod = null;
            this.enableMethod = null;
            this.unloadMethod = null;
            if (this.classLoader == null) return;
            try {
                this.classLoader.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to close MythicMembership runtime classloader", exception);
            } finally {
                this.classLoader = null;
            }
        }
    }

    private URL pluginJarUrl() {
        CodeSource codeSource = this.plugin.getClass().getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            throw new IllegalStateException("Unable to resolve MythicMembership plugin JAR");
        }
        return codeSource.getLocation();
    }

    private void invoke(Method method) {
        if (this.runtime == null || method == null) {
            throw new IllegalStateException("MythicMembership runtime is not loaded");
        }
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(this.classLoader);
            method.invoke(this.runtime);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access MythicMembership runtime lifecycle", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("MythicMembership runtime lifecycle failed", cause);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void closeAfterFailure(Exception failure) {
        if (this.classLoader == null) return;
        try {
            this.classLoader.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        this.classLoader = null;
    }
}
