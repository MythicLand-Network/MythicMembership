package cn.mythicland.memberlib;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MemberRecord(
        UUID playerUniqueId,
        MemberKey key,
        MemberScope scope,
        Instant expiresAt
) {
    public MemberRecord {
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
