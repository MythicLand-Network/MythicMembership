package cn.mythicland.mythicmembership.api;

import java.util.Objects;

public record MembershipDefinition(
        MembershipKey key,
        MembershipScope scope,
        String displayName,
        String prefix,
        int priority
) {
    public MembershipDefinition {
        Objects.requireNonNull(key, "membership key");
        Objects.requireNonNull(scope, "membership scope");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Membership display name cannot be blank");
        }
        prefix = Objects.requireNonNull(prefix, "membership prefix");
    }
}
