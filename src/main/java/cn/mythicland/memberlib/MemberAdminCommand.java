package cn.mythicland.memberlib;

import org.bukkit.plugin.java.JavaPlugin;

import cn.mythicland.framework.bukkit.CommandComponent;
import cn.mythicland.framework.component.InjectComponent;
import cn.mythicland.framework.command.AbstractCommand;
import cn.mythicland.framework.command.CommandArgument;
import cn.mythicland.framework.command.CommandContext;
import cn.mythicland.framework.command.CommandRoute;
import cn.mythicland.framework.command.SuggestionSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@InjectComponent
@CommandComponent
public final class MemberAdminCommand extends AbstractCommand {
    private static final String PERMISSION = "mythicmembership.admin";
    private static final DateTimeFormatter EXPIRY_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final JavaPlugin plugin;
    private final MemberService service;

    MemberAdminCommand(JavaPlugin plugin, MemberService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @CommandRoute(
            path = {"membership", "info"},
            permission = PERMISSION,
            usages = "/membership info <玩家|UUID>",
            minArguments = 1,
            maxArguments = 1,
            arguments = @CommandArgument(index = 0, source = SuggestionSource.ONLINE_PLAYERS)
    )
    private void info(CommandContext context) {
        this.withPlayer(context.sender(), context.argument(0), uniqueId -> this.service.load(uniqueId)
                .thenApply(view -> {
                    this.sendInfo(context.sender(), view);
                    return null;
                }));
    }

    @CommandRoute(
            path = {"membership", "set"},
            permission = PERMISSION,
            usages = "/membership set <玩家|UUID> <会员ID> <期限>",
            minArguments = 3,
            maxArguments = 3
    )
    private void set(CommandContext context) {
        this.changeTimed(
                context,
                this.service::set,
                "§a已为玩家 §e%s §a设置会员 §e%s§a。"
        );
    }

    @CommandRoute(
            path = {"membership", "extend"},
            permission = PERMISSION,
            usages = "/membership extend <玩家|UUID> <会员ID> <期限>",
            minArguments = 3,
            maxArguments = 3
    )
    private void extend(CommandContext context) {
        this.changeTimed(
                context,
                this.service::extend,
                "§a已为玩家 §e%s §a续期会员 §e%s§a。"
        );
    }

    @CommandRoute(
            path = {"membership", "permanent"},
            permission = PERMISSION,
            usages = "/membership permanent <玩家|UUID> <会员ID>",
            minArguments = 2,
            maxArguments = 2
    )
    private void permanent(CommandContext context) {
        this.change(
                context,
                uniqueId -> this.service.makePermanent(
                        uniqueId,
                        MemberKey.parse(context.argument(1))
                ),
                "§a已将玩家 §e%s §a的会员 §e%s §a设为永久。"
        );
    }

    @CommandRoute(
            path = {"membership", "revoke"},
            permission = PERMISSION,
            usages = "/membership revoke <玩家|UUID> <会员ID>",
            minArguments = 2,
            maxArguments = 2
    )
    private void revoke(CommandContext context) {
        this.change(
                context,
                uniqueId -> this.service.revoke(
                        uniqueId,
                        MemberKey.parse(context.argument(1))
                ),
                "§a已撤销玩家 §e%s §a的会员 §e%s§a。"
        );
    }

    private void changeTimed(
            CommandContext context,
            TimedMutation mutation,
            String successMessage
    ) {
        try {
            MemberKey key = MemberKey.parse(context.argument(1));
            Duration duration = MemberDurationParser.parse(context.argument(2));
            this.change(context, uniqueId -> mutation.apply(uniqueId, key, duration), successMessage);
        } catch (IllegalArgumentException exception) {
            this.send(context.sender(), "§c" + exception.getMessage());
        }
    }

    private void change(
            CommandContext context,
            Function<UUID, CompletableFuture<?>> mutation,
            String successMessage
    ) {
        this.withPlayer(
                context.sender(),
                context.argument(0),
                uniqueId -> mutation.apply(uniqueId)
                        .thenApply(result -> {
                            this.send(
                                    context.sender(),
                                    successMessage.formatted(context.argument(0), context.argument(1))
                            );
                            return null;
                        })
        );
    }

    private void withPlayer(
            CommandSender sender,
            String target,
            Function<UUID, CompletableFuture<?>> action
    ) {
        this.service.findPlayer(target).thenCompose(found -> found
                .map(action)
                .orElseGet(() -> CompletableFuture.failedFuture(
                        new IllegalArgumentException("未找到玩家: " + target)
                )))
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        this.send(sender, "§c" + this.message(throwable));
                    }
                });
    }

    private void sendInfo(CommandSender sender, MemberView view) {
        List<String> lines = new ArrayList<>();
        lines.add("§a玩家会员信息: §f" + view.playerUniqueId());
        view.records().stream()
                .sorted(Comparator.comparing(record -> record.key().value()))
                .forEach(record -> {
                    lines.add(this.describeName(record));
                    lines.add(this.describeDetails(record));
                });
        if (view.records().isEmpty()) lines.add("§c该玩家没有会员记录。");
        this.send(sender, lines.toArray(String[]::new));
    }

    private String describeName(MemberRecord record) {
        String name = this.service.definition(record.key())
                .map(MemberDefinition::displayName)
                .orElse(record.key().value());
        return "§7会员: §f" + ChatColor.translateAlternateColorCodes('&', name);
    }

    private String describeDetails(MemberRecord record) {
        return "§7ID: §f" + record.key() + " §8| §7作用域: §f" + record.scope()
                + " §8| §7有效期: " + this.expiry(record);
    }

    private String expiry(MemberRecord record) {
        if (record.permanent()) return "§a永久";
        Instant expiresAt = record.expiresAt();
        String formatted = EXPIRY_FORMATTER.format(expiresAt);
        return expiresAt.isAfter(Instant.now()) ? "§e" + formatted : "§c" + formatted + " (已过期)";
    }

    private void send(CommandSender sender, String... messages) {
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            for (String message : messages) sender.sendMessage(message);
        });
    }

    private String message(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    private interface TimedMutation {
        CompletableFuture<?> apply(
                UUID uniqueId,
                MemberKey key,
                Duration duration
        );
    }
}
