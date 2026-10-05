package cn.mythicland.memberlib;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class MemberChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUniqueId;

    public MemberChangedEvent(UUID playerUniqueId) {
        this.playerUniqueId = playerUniqueId;
    }

    public UUID playerUniqueId() {
        return this.playerUniqueId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
