package cn.mythicland.memberlib;

import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.Bukkit;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MemberService implements AutoCloseable {
    private final JavaPlugin plugin;
    private MemberRepository repository;
    private final ExecutorService executor;
    private final Map<MemberKey, MemberDefinition> definitions;
    private volatile Map<MemberKey, MemberDefinition> definitionsSnapshot = Map.of();
    private final Map<UUID, MemberView> recordsByPlayer;

    MemberService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "MythicMemberLib-Database");
            thread.setDaemon(true);
            return thread;
        });
        this.definitions = new ConcurrentHashMap<>();
        this.recordsByPlayer = new ConcurrentHashMap<>();
    }

    void connect(MemberRepository repository) {
        if (this.repository != null) throw new IllegalStateException("Member database is already connected");
        this.repository = repository;
    }

    public MemberRegistration register(MemberDefinition definition) {
        synchronized (this.definitions) {
            MemberDefinition previous = this.definitions.putIfAbsent(definition.key(), definition);
            if (previous != null) {
                throw new IllegalStateException("Membership definition is already registered: " + definition.key());
            }
            this.definitionsSnapshot = Map.copyOf(this.definitions);
        }
        return new Registration(definition);
    }

    public Optional<MemberDefinition> definition(MemberKey key) {
        return Optional.ofNullable(this.definitions.get(key));
    }

    public Collection<MemberDefinition> definitions() {
        return this.definitions.values().stream()
                .sorted(java.util.Comparator.comparing(definition -> definition.key().value()))
                .toList();
    }

    public boolean isLoaded(UUID playerUniqueId) {
        return this.recordsByPlayer.containsKey(playerUniqueId);
    }

    public MemberView view(UUID playerUniqueId) {
        MemberView cached = this.recordsByPlayer.get(playerUniqueId);
        if (cached == null) throw new IllegalStateException("Membership data is not loaded for " + playerUniqueId);
        return new MemberView(playerUniqueId, cached.records(), this.definitionSnapshot(), Instant.now());
    }

    public CompletableFuture<MemberView> load(UUID playerUniqueId) {
        return CompletableFuture.supplyAsync(() -> {
            List<MemberRecord> records = this.repository.load(playerUniqueId);
            MemberView view = new MemberView(playerUniqueId, records, this.definitionSnapshot(), Instant.now());
            this.recordsByPlayer.put(playerUniqueId, view);
            return view;
        }, this.executor);
    }

    public CompletableFuture<MemberView> lookup(UUID playerUniqueId) {
        return CompletableFuture.supplyAsync(() -> new MemberView(playerUniqueId,
                this.repository.load(playerUniqueId), this.definitionSnapshot(), Instant.now()), this.executor);
    }

    public CompletableFuture<MemberRecord> set(
            UUID playerUniqueId,
            MemberKey key,
            Duration duration
    ) {
        this.requirePositive(duration);
        MemberDefinition definition = this.requireDefinition(key);
        Instant expiry = Instant.now().plus(duration);
        return this.mutate(playerUniqueId, () -> this.repository.set(playerUniqueId, key, definition.scope(), expiry));
    }

    public CompletableFuture<MemberRecord> extend(
            UUID playerUniqueId,
            MemberKey key,
            Duration duration
    ) {
        return this.extendCommitted(playerUniqueId, key, duration)
                .thenCompose(record -> this.load(playerUniqueId).thenApply(ignored -> {
                    this.fireChanged(playerUniqueId);
                    return record;
                }));
    }

    public CompletableFuture<MemberRecord> extendCommitted(
            UUID playerUniqueId,
            MemberKey key,
            Duration duration
    ) {
        this.requirePositive(duration);
        MemberDefinition definition = this.requireDefinition(key);
        long nowMillis = System.currentTimeMillis();
        return CompletableFuture.supplyAsync(() -> this.repository.extend(
                playerUniqueId, key, definition.scope(), nowMillis, duration.toMillis()
        ), this.executor);
    }

    public CompletableFuture<MemberRecord> replace(
            UUID playerUniqueId,
            MemberKey key,
            Duration duration,
            Collection<MemberKey> replacedKeys
    ) {
        return this.replace(playerUniqueId, key, duration, replacedKeys, List.of());
    }

    public CompletableFuture<MemberRecord> replace(
            UUID playerUniqueId,
            MemberKey key,
            Duration duration,
            Collection<MemberKey> replacedKeys,
            Collection<MemberKey> grantedKeys
    ) {
        this.requirePositive(duration);
        MemberDefinition definition = this.requireDefinition(key);
        if (replacedKeys.contains(key)) throw new IllegalArgumentException("Replacement memberships include target: " + key);
        List<MemberDefinition> replaced = replacedKeys.stream().map(this::requireDefinition).toList();
        if (grantedKeys.stream().anyMatch(granted -> granted.equals(key) || replacedKeys.contains(granted))) {
            throw new IllegalArgumentException("Granted memberships overlap replacement memberships");
        }
        List<MemberDefinition> granted = grantedKeys.stream().distinct().map(this::requireDefinition).toList();
        long nowMillis = System.currentTimeMillis();
        return this.mutate(playerUniqueId, () -> this.repository.extend(
                playerUniqueId, key, definition.scope(), nowMillis, duration.toMillis(), replaced, granted));
    }

    public CompletableFuture<List<MemberRecord>> activeMembers(MemberKey key) {
        MemberDefinition definition = this.requireDefinition(key);
        return CompletableFuture.supplyAsync(
                () -> this.repository.activeMembers(key, definition.scope()), this.executor
        );
    }

    public CompletableFuture<MemberRecord> makePermanent(UUID playerUniqueId, MemberKey key) {
        MemberDefinition definition = this.requireDefinition(key);
        return this.mutate(playerUniqueId, () -> this.repository.set(playerUniqueId, key, definition.scope(), null));
    }

    public CompletableFuture<Boolean> revoke(UUID playerUniqueId, MemberKey key) {
        MemberDefinition definition = this.requireDefinition(key);
        return CompletableFuture.supplyAsync(
                () -> this.repository.revoke(playerUniqueId, key, definition.scope()),
                this.executor
        ).thenCompose(removed -> this.load(playerUniqueId).thenApply(ignored -> {
            if (removed) this.fireChanged(playerUniqueId);
            return removed;
        }));
    }

    public CompletableFuture<Boolean> revokeAll(UUID playerUniqueId) {
        return this.mutate(playerUniqueId, () -> this.repository.revokeAll(playerUniqueId));
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

    public void refresh(UUID playerUniqueId) {
        MemberView previous = this.recordsByPlayer.get(playerUniqueId);
        this.load(playerUniqueId).whenComplete((view, throwable) -> {
            if (throwable != null) {
                if (previous != null) this.recordsByPlayer.put(playerUniqueId, previous);
                this.plugin.getLogger().warning(
                        "Failed to refresh membership data for " + playerUniqueId + ": " + throwable.getMessage()
                );
                return;
            }
            if (previous != null && (!previous.records().equals(view.records()) || previous.records().stream()
                    .anyMatch(record -> previous.active(record.key()) != view.active(record.key())))) {
                this.fireChanged(playerUniqueId);
            }
        });
    }

    @Override
    public void close() {
        this.executor.shutdownNow();
        this.recordsByPlayer.clear();
        synchronized (this.definitions) {
            this.definitions.clear();
            this.definitionsSnapshot = Map.of();
        }
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
                new MemberChangedEvent(playerUniqueId)
        ));
    }

    private MemberDefinition requireDefinition(MemberKey key) {
        MemberDefinition definition = this.definitions.get(key);
        if (definition == null) throw new IllegalArgumentException("Membership definition is not registered: " + key);
        return definition;
    }

    private void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Membership duration must be positive");
        }
    }

    private Map<MemberKey, MemberDefinition> definitionSnapshot() {
        return this.definitionsSnapshot;
    }

    private final class Registration implements MemberRegistration {
        private final MemberDefinition definition;
        private boolean closed;

        private Registration(MemberDefinition definition) {
            this.definition = definition;
        }

        @Override
        public MemberDefinition definition() {
            return this.definition;
        }

        @Override
        public void close() {
            synchronized (MemberService.this.definitions) {
                if (this.closed) return;
                MemberService.this.definitions.remove(this.definition.key(), this.definition);
                MemberService.this.definitionsSnapshot = Map.copyOf(MemberService.this.definitions);
                this.closed = true;
            }
        }
    }
}
