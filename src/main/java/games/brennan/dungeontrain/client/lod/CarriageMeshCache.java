package games.brennan.dungeontrain.client.lod;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Owns the {@link BakedCarriageMesh} for every carriage currently at the far LOD tier, and bounds
 * how much baking happens in any one frame.
 *
 * <h2>Why a map and not a field on the sub-level</h2>
 *
 * <p>{@link DtLodRenderable} carries the far <em>flag</em> on the sub-level instance because a plain
 * field read is what the hot render path needs. The mesh is different: it owns GL buffers, and a
 * mixin-added field would have no reliable close hook when Sable drops a sub-level, leaking VRAM
 * silently. An explicit map gives an explicit lifecycle — {@link #sweepRemoved()} closes meshes for
 * sub-levels Sable has removed, so an unloaded train cannot leak.</p>
 *
 * <h2>Bake budget</h2>
 *
 * <p>Baking walks every block of a ~37-block carriage group and uploads GL buffers, so it is a
 * frame-visible cost. When a train first comes into view many carriages cross the threshold on the
 * same tick; without a cap that lands as one long hitch. {@link #BAKES_PER_TICK} spreads it, and
 * because {@link CarriageLod#demote} refuses to set the far flag until a mesh exists, a carriage
 * waiting its turn in the queue simply stays at the near tier — correct, just not yet cheap.</p>
 *
 * <p>Render-thread only: every entry point touches GL.</p>
 */
public final class CarriageMeshCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("games.brennan.dungeontrain.jitter");

    /**
     * Maximum bakes started per client tick. 2 keeps the worst case to roughly two carriage-group
     * tesselations per tick, which is well inside a frame at 20 TPS, while still filling a fresh
     * 15-carriage window (5 groups) within about three ticks of it coming into range.
     */
    static final int BAKES_PER_TICK = 2;

    private static final Map<ClientSubLevel, BakedCarriageMesh> MESHES = new IdentityHashMap<>();

    /** Reset each tick by {@link #beginTick()}. */
    private static int bakesThisTick;

    private CarriageMeshCache() {}

    /** Called once per client tick before the LOD reconcile, to refresh the per-tick bake budget. */
    static void beginTick() {
        bakesThisTick = 0;
    }

    /**
     * Ensure a baked mesh exists for {@code sl}. Returns false when one could not be produced this
     * tick — either the budget is spent or the sub-level's blocks are not loaded yet. Callers treat
     * false as "stay at the near tier and try again", never as an error.
     */
    static boolean ensureBaked(ClientSubLevel sl) {
        BakedCarriageMesh existing = MESHES.get(sl);
        if (existing != null && !existing.isClosed()) {
            if (!isSkyLightStale(sl, existing)) return true;
            // Stale lighting: drop it and fall through to a rebake, but only if the budget allows.
            // If it does not, keep drawing the slightly-wrong-brightness mesh this tick rather than
            // dropping the carriage back to the near tier and paying a full chunk render for it.
            if (bakesThisTick >= BAKES_PER_TICK) return true;
            drop(sl);
        }

        if (bakesThisTick >= BAKES_PER_TICK) return false;
        bakesThisTick++;

        BakedCarriageMesh mesh = CarriageMeshBaker.bake(sl);
        if (mesh == null) return false;

        MESHES.put(sl, mesh);
        return true;
    }

    /**
     * Largest sky-light-scale drift tolerated before a mesh is rebaked. Sable's scale is a 0–15
     * light level, so 1 means "rebake on any real change" while still ignoring the jitter of a
     * value recomputed every tick. A full day/night cycle therefore costs a handful of rebakes per
     * far carriage, spread by the per-tick budget.
     */
    static final int SKY_LIGHT_REBAKE_THRESHOLD = 1;

    private static boolean isSkyLightStale(ClientSubLevel sl, BakedCarriageMesh mesh) {
        return Math.abs(sl.getLatestSkyLightScale() - mesh.bakedSkyLightScale()) >= SKY_LIGHT_REBAKE_THRESHOLD;
    }

    /** The mesh to draw for {@code sl}, or null if it has none (i.e. it is not at the far tier). */
    @Nullable
    public static BakedCarriageMesh get(ClientSubLevel sl) {
        BakedCarriageMesh mesh = MESHES.get(sl);
        return mesh == null || mesh.isClosed() ? null : mesh;
    }

    /** Drop and free {@code sl}'s mesh. Idempotent. */
    static void drop(ClientSubLevel sl) {
        BakedCarriageMesh mesh = MESHES.remove(sl);
        if (mesh != null) mesh.close();
    }

    /**
     * Free meshes for sub-levels Sable has removed. Called each reconcile: a carriage can be culled
     * or split away without ever being promoted, and its mesh would otherwise hold VRAM for the
     * rest of the session.
     */
    static void sweepRemoved() {
        for (Iterator<Map.Entry<ClientSubLevel, BakedCarriageMesh>> it = MESHES.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<ClientSubLevel, BakedCarriageMesh> e = it.next();
            if (e.getKey().isRemoved()) {
                e.getValue().close();
                it.remove();
            }
        }
    }

    /** Free everything. Called on level unload / disconnect so meshes never outlive their world. */
    public static void clear() {
        if (!MESHES.isEmpty()) {
            LOGGER.debug("[lod] clearing {} baked carriage mesh(es)", MESHES.size());
        }
        MESHES.values().forEach(BakedCarriageMesh::close);
        MESHES.clear();
        bakesThisTick = 0;
    }

    /** Number of live baked meshes — for {@code /dungeontrain debug lod status}. */
    public static int size() {
        return MESHES.size();
    }
}
