package cn.mythicland.memberlib;

import cn.mythicland.framework.text.PlayerNameStyle;

import java.util.Objects;

public record MemberNameStyle(String prefix) {
    public MemberNameStyle {
        Objects.requireNonNull(prefix, "membership prefix");
    }

    public PlayerNameStyle playerNameStyle() {
        return new PlayerNameStyle(this.prefix);
    }

    public String nameColor() { return this.playerNameStyle().nameColor(); }
    public String plainName(String playerName) { return this.playerNameStyle().plainName(playerName); }
    public String coloredName(String playerName) { return this.playerNameStyle().coloredName(playerName); }
    public String prefixedName(String playerName) { return this.playerNameStyle().prefixedName(playerName); }
    public String plainPrefixedName(String playerName) { return this.playerNameStyle().plainPrefixedName(playerName); }
}
