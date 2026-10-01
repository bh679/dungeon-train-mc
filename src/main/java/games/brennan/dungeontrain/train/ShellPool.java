package games.brennan.dungeontrain.train;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The three carriage (shell) template pools, stored apart: <b>Room</b> templates in
 * {@code templates/}, <b>Half</b> in {@code templates/half/}, <b>Group</b> in {@code templates/group/}
 * — on the classpath, in the user tier and in the source tree alike.
 *
 * <p>A template's pool is where its file is, and its pool is its size: a Half template is a Half
 * box, a Group template a whole group. Ids are unique across the pools, so everything keyed by id
 * alone (weights, container sidecars) needs no pool; everything stored <i>beside</i> the template
 * (its variants / parts / contents-allow sidecars) builds its name with {@link #path}.</p>
 *
 * <p>Which pool each id is in is recorded by {@link CarriageVariantRegistry} as it scans; an id it
 * has not seen is a Room. No Minecraft types beyond that, so it unit-tests without a bootstrap.</p>
 */
public enum ShellPool {
    ROOM("", ContentsSize.ROOM),
    HALF("half", ContentsSize.HALF),
    GROUP("group", ContentsSize.FULL);

    private final String folder;
    private final ContentsSize size;

    ShellPool(String folder, ContentsSize size) {
        this.folder = folder;
        this.size = size;
    }

    /** The sub-folder under {@code templates/}; empty for Room, which is the folder itself. */
    public String folder() {
        return folder;
    }

    /** The box every template in this pool is. */
    public ContentsSize size() {
        return size;
    }

    /** The pool of {@code size}'s templates. */
    public static ShellPool of(ContentsSize size) {
        for (ShellPool p : values()) {
            if (p.size == size) return p;
        }
        return ROOM;
    }

    /** {@code raw} as a pool by its size key ({@code room} / {@code half} / {@code full}) or name. */
    public static Optional<ShellPool> parse(String raw) {
        if (raw == null) return Optional.empty();
        String k = raw.trim().toLowerCase(Locale.ROOT);
        for (ShellPool p : values()) {
            if (p.size.key().equals(k) || p.name().toLowerCase(Locale.ROOT).equals(k)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** {@code id} relative to {@code templates/} in this pool — {@code half/foo}, or {@code foo} for Room. */
    public String pathOf(String id) {
        return folder.isEmpty() ? id : folder + "/" + id;
    }

    // ---- id → pool, as the registry last scanned it ----------------------------------------

    private static volatile Map<String, ShellPool> POOLS = Map.of();
    /** Bumped on every change, so layouts memoised on pools can tell they are stale. */
    private static volatile int version;

    public static int version() {
        return version;
    }

    /** {@code id}'s pool; Room when the registry has not placed it anywhere else. */
    public static ShellPool poolOf(String id) {
        return POOLS.getOrDefault(id, ROOM);
    }

    /** {@code id} relative to {@code templates/} — where its template and sidecars live. */
    public static String path(String id) {
        return poolOf(id).pathOf(id);
    }

    /** Replace the whole map — the registry's reload. */
    public static synchronized void setAll(Map<String, ShellPool> pools) {
        POOLS = Map.copyOf(pools);
        version++;
    }

    /** Record one id's pool — a create, or a move between pools. */
    public static synchronized void set(String id, ShellPool pool) {
        Map<String, ShellPool> next = new HashMap<>(POOLS);
        if (pool == ROOM) next.remove(id);
        else next.put(id, pool);
        POOLS = Map.copyOf(next);
        version++;
    }

    /** Forget {@code id} — a delete. */
    public static synchronized void forget(String id) {
        if (!POOLS.containsKey(id)) return;
        Map<String, ShellPool> next = new HashMap<>(POOLS);
        next.remove(id);
        POOLS = Map.copyOf(next);
        version++;
    }
}
