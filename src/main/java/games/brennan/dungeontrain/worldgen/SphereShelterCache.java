package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.SphereGravityShelter.Exposure;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import java.util.function.Supplier;

/**
 * A short-lived memo of {@link SphereGravityShelter.Exposure} per entity. Whether something is inside, on top
 * of, or off the train changes rarely, while the scan that decides it reads dozens of carriage blocks; so each
 * verdict is kept for {@link #TTL_TICKS} and recomputed when it expires, when the entity's <em>anchor</em> (the
 * carriage Sable says is carrying it) changes, or when {@link #invalidate} is called for it (death, leaving the
 * level). Pure — no Minecraft types — so the expiry rules are unit-tested.
 *
 * <p>Not thread-safe: one instance per thread (the server tick, the client tick). Bounded by
 * {@link #MAX_ENTRIES}; when full, expired entries are dropped first, and the whole memo if that frees nothing.</p>
 */
public final class SphereShelterCache {

    /** Ticks a verdict is trusted for. */
    public static final int TTL_TICKS = 5;
    /** Most entities remembered at once; the band rarely has more than a few hundred in reach. */
    public static final int MAX_ENTRIES = 4096;

    private record Entry(Exposure exposure, Object anchor, long expiresAt) {}

    private final int ttl;
    private final int maxEntries;
    private final Int2ObjectOpenHashMap<Entry> entries = new Int2ObjectOpenHashMap<>();

    public SphereShelterCache() {
        this(TTL_TICKS, MAX_ENTRIES);
    }

    SphereShelterCache(int ttl, int maxEntries) {
        this.ttl = Math.max(1, ttl);
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * The cached verdict for {@code entityId} if it was computed less than {@code ttl} ticks before {@code now}
     * under the same {@code anchor} (compared by identity; {@code null} is a valid anchor), else the result of
     * {@code classify}, remembered until {@code now + ttl}.
     */
    public Exposure get(int entityId, Object anchor, long now, Supplier<Exposure> classify) {
        Entry hit = entries.get(entityId);
        if (hit != null && now < hit.expiresAt() && hit.anchor() == anchor) return hit.exposure();
        Exposure fresh = classify.get();
        if (hit == null && entries.size() >= maxEntries) makeRoom(now);
        entries.put(entityId, new Entry(fresh, anchor, now + ttl));
        return fresh;
    }

    /** Forget one entity (it died or left the level). */
    public void invalidate(int entityId) {
        entries.remove(entityId);
    }

    /** Forget everything (level unload, logout). */
    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** Drop every expired entry; if none was, drop them all — re-scanning is cheaper than growing unbounded. */
    private void makeRoom(long now) {
        int before = entries.size();
        ObjectIterator<Int2ObjectOpenHashMap.Entry<Entry>> it = entries.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            if (now >= it.next().getValue().expiresAt()) it.remove();
        }
        if (entries.size() == before) entries.clear();
    }
}
