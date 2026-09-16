package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.bukkit.task.ScheduledTask;
import cn.mythicland.framework.component.InjectComponent;
import org.bukkit.Bukkit;

@InjectComponent
final class MembershipRefreshTask {
    private final MembershipService membershipService;

    MembershipRefreshTask(MembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @ScheduledTask(delayTicks = 100L, periodTicks = 100L)
    private void refreshOnlinePlayers() {
        Bukkit.getOnlinePlayers().forEach(
                player -> this.membershipService.refresh(player.getUniqueId())
        );
    }
}
