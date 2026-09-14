package cn.mythicland.mythicmembership.api;

import java.util.Locale;

public record MembershipScope(String value) {
    public static final MembershipScope NETWORK = new MembershipScope("network");

    public MembershipScope {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid membership scope: " + value);
        }
    }

    public static MembershipScope of(String value) {
        if (value == null) throw new IllegalArgumentException("Membership scope is required");
        return new MembershipScope(value.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return this.value;
    }
}
