package cn.mythicland.mythicmembership.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface MembershipApi {
    MembershipRegistration register(MembershipDefinition definition);

    Optional<MembershipDefinition> definition(MembershipKey key);

    Collection<MembershipDefinition> definitions();

    MembershipView view(UUID playerUniqueId);

    CompletableFuture<MembershipView> load(UUID playerUniqueId);

    CompletableFuture<MembershipRecord> set(
            UUID playerUniqueId,
            MembershipKey key,
            Duration duration
    );

    CompletableFuture<MembershipRecord> extend(
            UUID playerUniqueId,
            MembershipKey key,
            Duration duration
    );

    CompletableFuture<MembershipRecord> makePermanent(UUID playerUniqueId, MembershipKey key);

    CompletableFuture<Boolean> revoke(UUID playerUniqueId, MembershipKey key);
}
