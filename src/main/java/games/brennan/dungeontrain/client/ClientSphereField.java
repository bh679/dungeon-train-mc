package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.worldgen.MixBand;
import games.brennan.dungeontrain.worldgen.SphereField;
import games.brennan.dungeontrain.worldgen.SpheresBand;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;

import java.util.List;

/**
 * The client's own copy of the spheres band's {@link SphereField}, so sphere gravity can pull the local
 * player without a per-tick sync. A sphere's centre and radius are pure in the generation seed (synced
 * once on login by {@code VoidBandSyncPacket}) and the COMMON config; the server's terrain samplers only
 * decide where a sphere's blocks are cut from, never where it sits, so stubs stand in for them here.
 *
 * <p>Rebuilt whenever the seed changes or the memoised {@link WorldGenCycle} is replaced (every COMMON
 * config load/reload invalidates it), which covers every key the field reads.</p>
 */
public final class ClientSphereField {

    /** Stand-in surface: only shifts the cut ({@code dy}), never the centre or radius. */
    private static final SphereField.SurfaceSampler FLAT = (x, z) -> 64;

    private static volatile boolean synced;
    private static volatile long seed;
    private static volatile SphereField field;
    private static volatile WorldGenCycle fieldCycle;

    private ClientSphereField() {}

    /** Apply the server's generation seed (login). */
    public static void update(long generationSeed) {
        seed = generationSeed;
        synced = true;
        field = null;
    }

    /** Forget the world on disconnect. */
    public static void reset() {
        synced = false;
        field = null;
        fieldCycle = null;
    }

    /** Placed spheres within {@code reach} of a point; empty before the sync, out of band, or with no train. */
    public static List<SphereField.Sphere> spheresNear(double x, double y, double z, double reach) {
        if (!synced || !ClientVoidBand.startsWithTrain() || !DungeonTrainCommonConfig.isSpheresEnabled()) return List.of();
        WorldGenCycle base = WorldGenCycle.fromConfig();
        WorldGenCycle cycle = MixBand.cycleFor(base, seed, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        return SpheresBand.spheresNear(fieldFor(base), cycle, x, y, z, reach);
    }

    private static SphereField fieldFor(WorldGenCycle base) {
        SphereField f = field;
        if (f != null && fieldCycle == base) return f;
        f = new SphereField(SpheresBand.paramsFromConfig(seed), FLAT, SpheresBand.configMixer(FLAT));
        field = f;
        fieldCycle = base;
        return f;
    }
}
