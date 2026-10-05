package cn.mythicland.memberlib;

import java.util.Objects;

public record MemberDefinition(
        MemberKey key,
        MemberScope scope,
        String displayName,
        String prefix,
        int priority
) {
    public MemberDefinition {
        Objects.requireNonNull(key, "membership key");
        Objects.requireNonNull(scope, "membership scope");
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Membership display name cannot be blank");
        }
        prefix = Objects.requireNonNull(prefix, "membership prefix");
    }
}
