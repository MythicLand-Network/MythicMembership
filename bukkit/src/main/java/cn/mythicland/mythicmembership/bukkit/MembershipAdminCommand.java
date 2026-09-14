package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.bukkit.CommandComponent;
import cn.mythicland.framework.command.*;
import cn.mythicland.mythicmembership.api.MembershipDefinition;
import cn.mythicland.mythicmembership.api.MembershipKey;
import cn.mythicland.mythicmembership.api.MembershipRecord;
import cn.mythicland.mythicmembership.api.MembershipView;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.time.Duration;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@CommandComponent
public final class MembershipAdminCommand extends AbstractCommand {
    private static final String PERMISSION = "mythicmembership.admin";

    private final MythicMembershipPlugin plugin;
    private final MembershipService service;

    MembershipAdminCommand(MythicMembershipPlugin plugin, MembershipService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @CommandRoute(
            path = {"membership"},
            permission = PERMISSION,
            usages = "/membership info|set|extend|permanent|revoke"
    )
    private void root(CommandContext context) {
        context.sender().sendMessage(ChatColor.YELLOW + "/membership info <玩家|UUID>");
        context.sender().sendMessage(ChatColor.YELLOW + "/membership set|extend <玩家|UUID> <会员ID> <期限>");
        context.sender().sendMessage(ChatColor.YELLOW + "/membership permanent|revoke <玩家|UUID> <会员ID>");
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
        this.changeTimed(context, this.service::set, "已设置");
    }

    @CommandRoute(
            path = {"membership", "extend"},
            permission = PERMISSION,
            usages = "/membership extend <玩家|UUID> <会员ID> <期限>",
            minArguments = 3,
            maxArguments = 3
    )
    private void extend(CommandContext context) {
        this.changeTimed(context, this.service::extend, "已续期");
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
                        MembershipKey.parse(context.argument(1))
                ),
                "已设为永久"
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
                        MembershipKey.parse(context.argument(1))
                ),
                "已撤销"
        );
    }

    private void changeTimed(
            CommandContext context,
            TimedMutation mutation,
            String success
    ) {
        MembershipKey key = MembershipKey.parse(context.argument(1));
        Duration duration = MembershipDurationParser.parse(context.argument(2));
        this.change(context, uniqueId -> mutation.apply(uniqueId, key, duration), success);
    }

    private void change(
            CommandContext context,
            Function<UUID, CompletableFuture<?>> mutation,
            String success
    ) {
        this.withPlayer(
                context.sender(),
                context.argument(0),
                uniqueId -> mutation.apply(uniqueId)
                        .thenApply(result -> {
                            this.send(context.sender(), ChatColor.GREEN + success + ": " + uniqueId);
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
                    if (throwable != null) this.send(sender, ChatColor.RED + this.message(throwable));
                });
    }

    private void sendInfo(CommandSender sender, MembershipView view) {
        this.send(sender, ChatColor.YELLOW + "会员状态: " + view.playerUniqueId());
        view.records().stream()
                .sorted(Comparator.comparing(record -> record.key().value()))
                .forEach(record -> this.send(sender, this.describe(view, record)));
        if (view.records().isEmpty()) this.send(sender, ChatColor.GRAY + "无会员记录");
    }

    private String describe(MembershipView view, MembershipRecord record) {
        String name = this.service.definition(record.key())
                .map(MembershipDefinition::displayName)
                .orElse(record.key().value());
        String expiry = record.permanent() ? "永久" : String.valueOf(record.expiresAt().toEpochMilli());
        return ChatColor.translateAlternateColorCodes(
                '&',
                "&7- " + name + " &8[" + record.key() + "] &7" + expiry
        );
    }

    private void send(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(this.plugin, () -> sender.sendMessage(message));
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
                MembershipKey key,
                Duration duration
        );
    }
}
