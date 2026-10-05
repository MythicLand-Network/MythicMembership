package cn.mythicland.memberlib;

import cn.mythicland.framework.bukkit.task.ScheduledTask;
import cn.mythicland.framework.component.InjectComponent;
import org.bukkit.Bukkit;

@InjectComponent
final class MemberRefreshTask {
    private final MemberService membershipService;

    MemberRefreshTask(MemberService membershipService) {
        this.membershipService = membershipService;
    }

    @ScheduledTask(delayTicks = 100L, periodTicks = 100L)
    private void refreshOnlinePlayers() {
        Bukkit.getOnlinePlayers().forEach(
                player -> this.membershipService.refresh(player.getUniqueId())
        );
    }
}
