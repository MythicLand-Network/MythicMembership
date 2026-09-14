package cn.mythicland.mythicmembership.api;

import java.util.Locale;

public record MembershipKey(String value) {
    public MembershipKey {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Membership key must use namespace:value format: " + value);
        }
    }

    public static MembershipKey of(String namespace, String value) {
        if (namespace == null || value == null) {
            throw new IllegalArgumentException("Membership namespace and value are required");
        }
        return new MembershipKey(
                namespace.trim().toLowerCase(Locale.ROOT) + ":" + value.trim().toLowerCase(Locale.ROOT)
        );
    }

    public static MembershipKey parse(String value) {
        if (value == null) throw new IllegalArgumentException("Membership key is required");
        return new MembershipKey(value.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return this.value;
    }
}
