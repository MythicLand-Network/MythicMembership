package cn.mythicland.memberlib;

import java.util.Locale;

public record MemberKey(String value) {
    public MemberKey {
        if (value == null || !value.matches("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("Membership key must use namespace:value format: " + value);
        }
    }

    public static MemberKey of(String namespace, String value) {
        if (namespace == null || value == null) {
            throw new IllegalArgumentException("Membership namespace and value are required");
        }
        return new MemberKey(
                namespace.trim().toLowerCase(Locale.ROOT) + ":" + value.trim().toLowerCase(Locale.ROOT)
        );
    }

    public static MemberKey parse(String value) {
        if (value == null) throw new IllegalArgumentException("Membership key is required");
        return new MemberKey(value.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return this.value;
    }
}
