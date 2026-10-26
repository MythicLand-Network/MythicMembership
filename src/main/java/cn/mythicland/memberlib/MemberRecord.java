package cn.mythicland.memberlib;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MemberRecord(
        UUID playerUniqueId,
        MemberKey key,
        MemberScope scope,
        Instant expiresAt,
        Pause pause
) {
    public MemberRecord(UUID playerUniqueId, MemberKey key, MemberScope scope, Instant expiresAt) {
        this(playerUniqueId, key, scope, expiresAt, null);
    }

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
        if (this.pause != null && (this.pause.resumesAt() == null || instant.isBefore(this.pause.resumesAt()))) return false;
        return this.expiresAt == null || this.expiresAt.isAfter(instant);
    }

    long remainingMillisAt(Instant instant) {
        if (this.pause != null && (this.pause.resumesAt() == null || instant.isBefore(this.pause.resumesAt()))) {
            return this.pause.remainingMillis();
        }
        return Math.max(0L, this.expiresAt.toEpochMilli() - instant.toEpochMilli());
    }

    public record Pause(MemberKey blocker, long remainingMillis, Instant resumesAt) {
    }
}
