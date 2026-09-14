package cn.mythicland.mythicmembership.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MembershipRecord(
        UUID playerUniqueId,
        MembershipKey key,
        MembershipScope scope,
        Instant expiresAt
) {
    public MembershipRecord {
        Objects.requireNonNull(playerUniqueId, "player UUID");
        Objects.requireNonNull(key, "membership key");
        Objects.requireNonNull(scope, "membership scope");
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(this.expiresAt);
    }

    public boolean permanent() {
        return this.expiresAt == null;
    }

    public boolean activeAt(Instant instant) {
        return this.expiresAt == null || this.expiresAt.isAfter(instant);
    }
}
