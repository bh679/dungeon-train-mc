package games.brennan.dungeontrain.worldgen;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The <b>copy-on-write table</b> behind {@link VanillaBiomeTwins}: for each key (a live biome, by
 * identity) its twin and the spawns that go with it.
 *
 * <p>Readers — every tinted block on every chunk-mesh thread, every snow/ice check on worldgen
 * workers — take one volatile read and never lock. {@link #publish} / {@link #clear} swap in a fresh
 * unmodifiable map under a write lock, so a reader only ever sees a finished map.</p>
 *
 * <p>Write order is the contract: {@link #publish} puts spawns in before twins, so a reader that
 * finds a twin finds its spawns; {@link #clear} drops twins first, so a reader racing it gets a
 * {@code null} twin or {@code null} spawns — both mean "let the live biome answer".</p>
 *
 * <p>Free of Minecraft types so it is unit-testable without a NeoForge bootstrap.</p>
 */
public final class TwinTable<K, T, S> {

    private volatile Map<K, T> twins = Map.of();
    private volatile Map<K, S> spawns = Map.of();
    private final Object writeLock = new Object();

    /** The twin of {@code key}, or {@code null}. */
    public T twin(K key) {
        return twins.get(key);
    }

    /** The spawns kept for {@code key}, or {@code null}. */
    public S spawns(K key) {
        return spawns.get(key);
    }

    public int size() {
        return twins.size();
    }

    /** Add a batch to the table. Copies both maps — later changes to the arguments are not seen. */
    public void publish(Map<K, T> newTwins, Map<K, S> newSpawns) {
        synchronized (writeLock) {
            // Spawns first: a reader that sees a new twin must also find its spawns.
            spawns = merged(spawns, newSpawns);
            twins = merged(twins, newTwins);
        }
    }

    public void clear() {
        synchronized (writeLock) {
            twins = Map.of();
            spawns = Map.of();
        }
    }

    /** A new unmodifiable identity map: {@code base} plus {@code added} — never mutates either. */
    private static <K, V> Map<K, V> merged(Map<K, V> base, Map<K, V> added) {
        Map<K, V> copy = new IdentityHashMap<>(base);
        copy.putAll(added);
        return Collections.unmodifiableMap(copy);
    }
}
