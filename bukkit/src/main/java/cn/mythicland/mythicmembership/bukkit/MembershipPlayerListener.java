package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.framework.bukkit.ListenerComponent;
import cn.mythicland.framework.component.InjectComponent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@InjectComponent
@ListenerComponent
final class MembershipPlayerListener implements Listener {
    private static final String LOAD_FAILED_MESSAGE = "§c会员数据加载失败, 请稍后重试。";

    private final MembershipService service;

    MembershipPlayerListener(MembershipService service) {
        this.service = service;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        try {
            this.service.rememberName(event.getUniqueId(), event.getName());
            this.service.load(event.getUniqueId()).join();
        } catch (RuntimeException exception) {
            this.service.evict(event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, LOAD_FAILED_MESSAGE);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.service.evict(event.getPlayer().getUniqueId());
    }
}
