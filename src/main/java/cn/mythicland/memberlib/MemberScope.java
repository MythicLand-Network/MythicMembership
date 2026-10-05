package cn.mythicland.memberlib;

import java.util.Locale;

public record MemberScope(String value) {
    public static final MemberScope NETWORK = new MemberScope("network");

    public MemberScope {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Invalid membership scope: " + value);
        }
    }

    public static MemberScope of(String value) {
        if (value == null) throw new IllegalArgumentException("Membership scope is required");
        return new MemberScope(value.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return this.value;
    }
}
