package games.brennan.dungeontrain.client.wind;

import games.brennan.dungeontrain.wind.WindSample;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * The train wind as the rest of the air feels it: ambient particles drifting downwind (smoke, snow, ash, petals,
 * splashes — {@link WindParticleTypes}) and the rain and snow sheets slanting over. Refreshed once per client tick
 * by {@link WindClientEvents} from the same wind the streaks show, so a gust bends everything at once.
 *
 * <h2>Rain and snow</h2>
 * Rain slants: its sheet is sheared downwind. Snow doesn't lean; its flakes are blown sideways instead, by
 * scrolling the snow texture across each sheet at the speed snowflakes drift ({@link #snowScrollU}).
 *
 * <h2>Ground frame</h2>
 * Unlike the streaks, nothing here gets the carriage's velocity: a campfire beside the track smokes downwind and
 * stays behind as the train passes.
 *
 * <h2>Shelter</h2>
 * The same rule as the streaks ({@link WindVisualShelter}): nothing drifts inside a carriage or under terrain.
 * Particles re-check every {@link #SHELTER_RECHECK} ticks, and the answer per block is cached for the tick, so a
 * cloud of rain splashes on one roof costs one lookup.
 */
public final class AmbientWind {

    /** Drift speed (blocks/tick) for weight 1, as a share of the streak speed at the same push. */
    static final double SPEED_FACTOR = 0.6;
    /** Share of the gap to the target drift a particle closes each tick (it picks up a gust over ~half a second). */
    public static final double DRIFT_EASE = 0.15;
    /** Ticks between a particle's shelter checks. */
    public static final int SHELTER_RECHECK = 8;

    /** Rain sheet slant (horizontal blocks per block of height) per unit of push, and its cap. */
    static final double RAIN_SLANT_PER_PUSH = 15.0;
    static final double RAIN_MAX_SLANT = 0.6;
    /**
     * Widest weather radius vanilla's column tables allow (its {@code rainSizeX/Z} are 32 x 32, centred on the
     * camera), and how high the curtain reaches above/below eye level, which is how far a lean pushes its ends.
     */
    static final int MAX_RAIN_RADIUS = 15;
    static final double CURTAIN_HALF_HEIGHT = 10.0;
    /** Share of the gap to the target slant (and snow scroll speed) closed each tick, so a gust swings over and back. */
    static final double SLANT_EASE = 0.15;

    private static final byte UNKNOWN = 0;
    private static final byte OPEN = 1;
    private static final byte SHELTERED = 2;

    private static volatile boolean active = false;
    private static volatile double driftX = 0.0;
    private static volatile double driftZ = 0.0;
    private static volatile double rainSlantX = 0.0;
    private static volatile double rainSlantZ = 0.0;
    /** Snow scroll speed (blocks/tick, eased) and how far it has scrolled since login (blocks, never wrapped). */
    private static double snowVelX = 0.0;
    private static double snowVelZ = 0.0;
    private static double snowScrollX = 0.0;
    private static double snowScrollZ = 0.0;

    /** Shelter per block for this tick; client thread only (particles tick there too). */
    private static final Long2ByteOpenHashMap SHELTER = new Long2ByteOpenHashMap();

    private AmbientWind() {}

    static void set(WindSample visual) {
        SHELTER.clear();
        double push = Math.max(0.0, visual.strength());
        double dx = visual.dirX();
        double dz = visual.dirZ();
        double speed = push <= 0.0 ? 0.0
                : (WindyDrive.SPEED_BASE + push * WindyDrive.SPEED_PER_PUSH) * SPEED_FACTOR;
        driftX = dx * speed;
        driftZ = dz * speed;
        double rain = slantTarget(push, RAIN_SLANT_PER_PUSH, RAIN_MAX_SLANT);
        rainSlantX = ease(rainSlantX, dx * rain);
        rainSlantZ = ease(rainSlantZ, dz * rain);
        stepSnow(driftX * WindParticleTypes.LIGHT, driftZ * WindParticleTypes.LIGHT);
        active = true;
    }

    /** No wind here (off, another dimension, a dimensional carriage): stop drifting and let the sheets settle. */
    static void release() {
        SHELTER.clear();
        active = false;
        driftX = 0.0;
        driftZ = 0.0;
        rainSlantX = ease(rainSlantX, 0.0);
        rainSlantZ = ease(rainSlantZ, 0.0);
        stepSnow(0.0, 0.0);
    }

    /** Scroll the snow by this tick's speed, then ease the speed toward {@code (targetX, targetZ)}. */
    private static void stepSnow(double targetX, double targetZ) {
        snowScrollX += snowVelX;
        snowScrollZ += snowVelZ;
        snowVelX = ease(snowVelX, targetX);
        snowVelZ = ease(snowVelZ, targetZ);
    }

    /** Clear everything at once (logging out). */
    static void reset() {
        release();
        rainSlantX = rainSlantZ = snowVelX = snowVelZ = snowScrollX = snowScrollZ = 0.0;
    }

    public static boolean active() {
        return active;
    }

    /** Target drift velocity for weight 1, blocks/tick along X (ground frame). */
    public static double driftX() {
        return driftX;
    }

    public static double driftZ() {
        return driftZ;
    }

    /** Horizontal offset per block of height for the rain sheet. */
    public static double rainSlantX() {
        return rainSlantX;
    }

    public static double rainSlantZ() {
        return rainSlantZ;
    }

    /**
     * Weather radius (columns) for vanilla's {@code base}: widened by how far the lean pushes the curtain's ends
     * sideways, so the shifted sheets still cover the sky all round. Unchanged with no lean.
     */
    public static int rainRadius(int base) {
        return radiusFor(base, Math.hypot(rainSlantX, rainSlantZ));
    }

    static int radiusFor(int base, double slant) {
        if (slant <= 0.0) return base;
        return Math.min(MAX_RAIN_RADIUS, Math.max(base, base + (int) Math.ceil(slant * CURTAIN_HALF_HEIGHT)));
    }

    /** True while the snow is scrolling at all (skip the per-vertex work otherwise). */
    public static boolean snowScrolling() {
        return snowVelX != 0.0 || snowVelZ != 0.0 || snowScrollX != 0.0 || snowScrollZ != 0.0;
    }

    /**
     * Texture U offset for a snow sheet whose U runs along world {@code (axisX, axisZ)} (unit), so its flakes move
     * downwind: minus the snow's scrolled distance along that axis, interpolated by {@code partialTick} and kept
     * to 0..1 (the snow texture repeats).
     */
    public static float snowScrollU(double axisX, double axisZ, float partialTick) {
        return scrollU(snowScrollX + snowVelX * partialTick, snowScrollZ + snowVelZ * partialTick, axisX, axisZ);
    }

    static float scrollU(double scrollX, double scrollZ, double axisX, double axisZ) {
        double along = scrollX * axisX + scrollZ * axisZ;
        return (float) -(along - Math.floor(along));
    }

    /** True when the wind does not reach this point (inside a carriage, under terrain); cached per block per tick. */
    public static boolean sheltered(ClientLevel level, double x, double y, double z) {
        long key = BlockPos.asLong((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        byte known = SHELTER.get(key);
        if (known != UNKNOWN) return known == SHELTERED;
        boolean s = WindVisualShelter.sheltered(level, x, y, z);
        SHELTER.put(key, s ? SHELTERED : OPEN);
        return s;
    }

    /** Sheet slant for a push: proportional, capped. */
    static double slantTarget(double push, double perPush, double max) {
        return Math.min(max, Math.max(0.0, push) * perPush);
    }

    static double ease(double current, double target) {
        double next = current + (target - current) * SLANT_EASE;
        return Math.abs(next) < 1.0e-4 && target == 0.0 ? 0.0 : next;
    }
}
