package cn.mythicland.mythicmembership.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MembershipView {
    private final UUID playerUniqueId;
    private final List<MembershipRecord> records;
    private final Map<MembershipKey, MembershipDefinition> definitions;
    private final Instant observedAt;

    public MembershipView(
            UUID playerUniqueId,
            List<MembershipRecord> records,
            Map<MembershipKey, MembershipDefinition> definitions,
            Instant observedAt
    ) {
        this.playerUniqueId = playerUniqueId;
        this.records = List.copyOf(records);
        this.definitions = Map.copyOf(definitions);
        this.observedAt = observedAt;
    }

    public UUID playerUniqueId() {
        return this.playerUniqueId;
    }

    public List<MembershipRecord> records() {
        return this.records;
    }

    public Optional<MembershipRecord> find(MembershipKey key) {
        return this.records.stream().filter(record -> record.key().equals(key)).findFirst();
    }

    public boolean active(MembershipKey key) {
        return this.find(key).filter(record -> record.activeAt(this.observedAt)).isPresent();
    }

    public Optional<ActiveMembership> primary(Set<MembershipScope> scopes) {
        return this.records.stream()
                .filter(record -> scopes.contains(record.scope()) && record.activeAt(this.observedAt))
                .map(record -> {
                    MembershipDefinition definition = this.definitions.get(record.key());
                    return definition == null ? null : new ActiveMembership(record, definition);
                })
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingInt(value -> value.definition().priority()));
    }
}
