package cn.mythicland.mythicmembership.bukkit;

import cn.mythicland.mythicmembership.api.MembershipApi;
import cn.mythicland.mythicmembership.api.MembershipChangedEvent;
import cn.mythicland.mythicmembership.api.MembershipDefinition;
import cn.mythicland.mythicmembership.api.MembershipKey;
import cn.mythicland.mythicmembership.api.MembershipRecord;
import cn.mythicland.mythicmembership.api.MembershipRegistration;
import cn.mythicland.mythicmembership.api.MembershipView;
import org.bukkit.Bukkit;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MembershipService implements MembershipApi, AutoCloseable {
    private final MythicMembershipPlugin plugin;
    private final MembershipRepository repository;
    private final ExecutorService executor;
    private final Map<MembershipKey, MembershipDefinition> definitions;
    private final Map<UUID, List<MembershipRecord>> recordsByPlayer;

    MembershipService(MythicMembershipPlugin plugin, MembershipRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MythicMembership-Database");
            thread.setDaemon(true);
            return thread;
        });
        this.definitions = new ConcurrentHashMap<>();
        this.recordsByPlayer = new ConcurrentHashMap<>();
    }

    @Override
    public MembershipRegistration register(MembershipDefinition definition) {
        MembershipDefinition previous = this.definitions.putIfAbsent(definition.key(), definition);
        if (previous != null) {
            throw new IllegalStateException("Membership definition is already registered: " + definition.key());
        }
        return new Registration(definition);
    }

    @Override
    public Optional<MembershipDefinition> definition(MembershipKey key) {
        return Optional.ofNullable(this.definitions.get(key));
    }

    @Override
    public Collection<MembershipDefinition> definitions() {
        return this.definitions.values().stream()
                .sorted(java.util.Comparator.comparing(definition -> definition.key().value()))
                .toList();
    }

    @Override
    public MembershipView view(UUID playerUniqueId) {
        List<MembershipRecord> records = this.recordsByPlayer.get(playerUniqueId);
        if (records == null) throw new IllegalStateException("Membership data is not loaded for " + playerUniqueId);
        return new MembershipView(playerUniqueId, records, this.definitionSnapshot(), Instant.now());
    }

    @Override
    public CompletableFuture<MembershipView> load(UUID playerUniqueId) {
        return CompletableFuture.supplyAsync(() -> {
            List<MembershipRecord> records = this.repository.load(playerUniqueId);
            this.recordsByPlayer.put(playerUniqueId, records);
            return new MembershipView(playerUniqueId, records, this.definitionSnapshot(), Instant.now());
        }, this.executor);
    }

    @Override
    public CompletableFuture<MembershipRecord> set(
            UUID playerUniqueId,
            MembershipKey key,
            Duration duration
    ) {
        this.requirePositive(duration);
        MembershipDefinition definition = this.requireDefinition(key);
        Instant expiry = Instant.now().plus(duration);
        return this.mutate(playerUniqueId, () -> this.repository.set(playerUniqueId, key, definition.scope(), expiry));
    }

    @Override
    public CompletableFuture<MembershipRecord> extend(
            UUID playerUniqueId,
            MembershipKey key,
            Duration duration
    ) {
        this.requirePositive(duration);
        MembershipDefinition definition = this.requireDefinition(key);
        List<MembershipRecord> current = this.recordsByPlayer.get(playerUniqueId);
        if (current != null && current.stream().anyMatch(record -> record.key().equals(key) && record.permanent())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Permanent membership cannot be extended: " + key)
            );
        }
        long nowMillis = System.currentTimeMillis();
        return this.mutate(playerUniqueId, () -> this.repository.extend(
                playerUniqueId, key, definition.scope(), nowMillis, duration.toMillis()
        ));
    }

    @Override
    public CompletableFuture<MembershipRecord> makePermanent(UUID playerUniqueId, MembershipKey key) {
        MembershipDefinition definition = this.requireDefinition(key);
        return this.mutate(playerUniqueId, () -> this.repository.set(playerUniqueId, key, definition.scope(), null));
    }

    @Override
    public CompletableFuture<Boolean> revoke(UUID playerUniqueId, MembershipKey key) {
        MembershipDefinition definition = this.requireDefinition(key);
        return CompletableFuture.supplyAsync(
                () -> this.repository.revoke(playerUniqueId, key, definition.scope()),
                this.executor
        ).thenCompose(removed -> this.load(playerUniqueId).thenApply(ignored -> {
            if (removed) this.fireChanged(playerUniqueId);
            return removed;
        }));
    }

    void rememberName(UUID playerUniqueId, String playerName) {
        this.repository.rememberName(playerUniqueId, playerName);
    }

    CompletableFuture<Optional<UUID>> findPlayer(String input) {
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(input);
        if (online != null) return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        return CompletableFuture.supplyAsync(() -> this.repository.findPlayer(input), this.executor);
    }

    void evict(UUID playerUniqueId) {
        this.recordsByPlayer.remove(playerUniqueId);
    }

    void refresh(UUID playerUniqueId) {
        List<MembershipRecord> previous = this.recordsByPlayer.get(playerUniqueId);
        this.load(playerUniqueId).whenComplete((view, throwable) -> {
            if (throwable != null) {
                if (previous != null) this.recordsByPlayer.put(playerUniqueId, previous);
                this.plugin.getLogger().warning(
                        "Failed to refresh membership data for " + playerUniqueId + ": " + throwable.getMessage()
                );
                return;
            }
            if (previous != null && !previous.equals(view.records())) this.fireChanged(playerUniqueId);
        });
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
        this.recordsByPlayer.clear();
        this.definitions.clear();
    }

    private <T> CompletableFuture<T> mutate(UUID playerUniqueId, java.util.concurrent.Callable<T> action) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Membership mutation failed", exception);
            }
        }, this.executor).thenCompose(result -> this.load(playerUniqueId).thenApply(ignored -> {
            this.fireChanged(playerUniqueId);
            return result;
        }));
    }

    private void fireChanged(UUID playerUniqueId) {
        Bukkit.getScheduler().runTask(this.plugin, () -> Bukkit.getPluginManager().callEvent(
                new MembershipChangedEvent(playerUniqueId)
        ));
    }

    private MembershipDefinition requireDefinition(MembershipKey key) {
        MembershipDefinition definition = this.definitions.get(key);
        if (definition == null) throw new IllegalArgumentException("Membership definition is not registered: " + key);
        return definition;
    }

    private void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Membership duration must be positive");
        }
    }

    private Map<MembershipKey, MembershipDefinition> definitionSnapshot() {
        return Map.copyOf(new LinkedHashMap<>(this.definitions));
    }

    private final class Registration implements MembershipRegistration {
        private final MembershipDefinition definition;
        private boolean closed;

        private Registration(MembershipDefinition definition) {
            this.definition = definition;
        }

        @Override
        public MembershipDefinition definition() {
            return this.definition;
        }

        @Override
        public void close() {
            if (this.closed) return;
            MembershipService.this.definitions.remove(this.definition.key(), this.definition);
            this.closed = true;
        }
    }
}
