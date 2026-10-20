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

    /** Sheet slant (horizontal blocks per block of height) per unit of push, and its cap. Snow is lighter. */
    static final double RAIN_SLANT_PER_PUSH = 15.0;
    static final double RAIN_MAX_SLANT = 0.6;
    static final double SNOW_SLANT_PER_PUSH = 30.0;
    static final double SNOW_MAX_SLANT = 1.0;
    /** Share of the gap to the target slant closed each tick, so a gust swings the rain over and back. */
    static final double SLANT_EASE = 0.15;

    private static final byte UNKNOWN = 0;
    private static final byte OPEN = 1;
    private static final byte SHELTERED = 2;

    private static volatile boolean active = false;
    private static volatile double driftX = 0.0;
    private static volatile double driftZ = 0.0;
    private static volatile double rainSlantX = 0.0;
    private static volatile double rainSlantZ = 0.0;
    private static volatile double snowSlantX = 0.0;
    private static volatile double snowSlantZ = 0.0;

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
        double snow = slantTarget(push, SNOW_SLANT_PER_PUSH, SNOW_MAX_SLANT);
        rainSlantX = ease(rainSlantX, dx * rain);
        rainSlantZ = ease(rainSlantZ, dz * rain);
        snowSlantX = ease(snowSlantX, dx * snow);
        snowSlantZ = ease(snowSlantZ, dz * snow);
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
        snowSlantX = ease(snowSlantX, 0.0);
        snowSlantZ = ease(snowSlantZ, 0.0);
    }

    /** Clear everything at once (logging out). */
    static void reset() {
        release();
        rainSlantX = rainSlantZ = snowSlantX = snowSlantZ = 0.0;
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

    /** Horizontal offset per block of height for the rain or snow sheet. */
    public static double slantX(boolean snow) {
        return snow ? snowSlantX : rainSlantX;
    }

    public static double slantZ(boolean snow) {
        return snow ? snowSlantZ : rainSlantZ;
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
