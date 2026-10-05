package cn.mythicland.memberlib;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MemberView {
    private final UUID playerUniqueId;
    private final List<MemberRecord> records;
    private final Map<MemberKey, MemberDefinition> definitions;
    private final Instant observedAt;

    public MemberView(
            UUID playerUniqueId,
            List<MemberRecord> records,
            Map<MemberKey, MemberDefinition> definitions,
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

    public List<MemberRecord> records() {
        return this.records;
    }

    public Optional<MemberRecord> find(MemberKey key) {
        return this.records.stream().filter(record -> record.key().equals(key)).findFirst();
    }

    public boolean active(MemberKey key) {
        return this.find(key).filter(record -> record.activeAt(this.observedAt)).isPresent();
    }

    public Optional<ActiveMember> primary(Set<MemberScope> scopes) {
        return this.records.stream()
                .filter(record -> scopes.contains(record.scope()) && record.activeAt(this.observedAt))
                .map(record -> {
                    MemberDefinition definition = this.definitions.get(record.key());
                    return definition == null ? null : new ActiveMember(record, definition);
                })
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingInt(value -> value.definition().priority()));
    }
}
