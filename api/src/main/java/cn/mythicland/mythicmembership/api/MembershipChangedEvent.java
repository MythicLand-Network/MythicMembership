package cn.mythicland.mythicmembership.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

public final class MembershipChangedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerUniqueId;

    public MembershipChangedEvent(UUID playerUniqueId) {
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
