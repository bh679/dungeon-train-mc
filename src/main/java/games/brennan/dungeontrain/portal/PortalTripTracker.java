package games.brennan.dungeontrain.portal;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * What a player did on one trip through a dimensional carriage, for the two advancements that ask.
 *
 * <h2>Why a trip is remembered rather than measured at the door</h2>
 * <p>Both questions are about a journey, not a position. "Was this a <i>different</i> way out" can
 * only be answered against where the player came in, and the way in is a corridor that may be a long
 * way behind them by the time they leave — through a copy the endless modes scattered somewhere else
 * entirely ({@link PortalExitSites}). So the arrival point is recorded when the swap fires and kept
 * until the matching way out fires, which is the only pair of moments that describes the trip.</p>
 *
 * <p>"Is the player really <i>in</i> the room" is the same shape of question. Crossing the midpoint
 * puts them in the twin corridor, which is still the doorway — the room proper starts where the
 * corridor stops. A dwell counted in scans rather than a single sample is what makes the difference
 * between walking through a portal carriage and going somewhere.</p>
 *
 * <h2>In memory only, and that is the right lifetime</h2>
 * <p>A trip that does not finish this session did not happen: the structures themselves
 * ({@code PortalCarriageEvents.STRUCTURES}) are in-memory and re-stamped on approach, so a
 * remembered entry point would outlive the room it referred to. Same reasoning as
 * {@link PortalExitBindings}, and the same idiom.</p>
 *
 * <p>No Minecraft types, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class PortalTripTracker {

    /**
     * How far from the entry corridor a way out has to be to count as a new one, in blocks.
     *
     * <p>Chosen to clear an ordinary trip. The walk from the entry twin to the pair's own exit twin
     * is one corridor plus one room ({@code PortalStructure.exitTwinOffsetX}); across every room
     * shipped in {@code data/dungeontrain/portals/dimensional_carriage} that is 19–34 blocks at the default carriage
     * dims, so the pair's own way out never earns this and only a corridor an endless mode stood
     * elsewhere ({@link PortalExitSites}) does.</p>
     *
     * <p><b>Not a proof, a margin.</b> {@link DimensionalCarriageLayout#MAX_LENGTH} is 48, so a room authored
     * longer than about 37 would put its own exit past this line and earn the advancement on a
     * straight walk through. That is a fair reading of "at least 50 blocks from the entrance" rather
     * than a bug — but it is the thing to re-check if a much longer room is ever authored.</p>
     */
    public static final double EXIT_DISTANCE_BLOCKS = 50.0;

    /**
     * Consecutive scans in the room body before the room counts as entered.
     *
     * <p>Ten scans at {@code BoardingProgressEvents.SCAN_PERIOD_TICKS} = 100 ticks = 5 seconds. Long
     * enough that a player who crosses a portal carriage without stopping never earns it, short
     * enough that a player who has walked into the room and looked around always does.</p>
     */
    public static final int DWELL_SCANS = 10;

    /** Where a player entered the room, and which pair's room it is. */
    public record Trip(int pairKey, double x, double z) {}

    /** Player → the trip they are currently on, absent when they are not in a room. */
    private static final Map<UUID, Trip> TRIPS = new HashMap<>();

    /** Player → consecutive scans spent in a room body. Cleared the moment they leave one. */
    private static final Map<UUID, Integer> DWELL = new HashMap<>();

    private PortalTripTracker() {}

    /**
     * Record where {@code uuid} arrived in the room, replacing any trip already in progress.
     *
     * <p>Replacing rather than keeping the first: a player who has gone back out to the train and
     * walked in again is on a new trip, and the way in they should be measured against is the one
     * they just used.</p>
     */
    public static void noteEntered(UUID uuid, int pairKey, double x, double z) {
        TRIPS.put(uuid, new Trip(pairKey, x, z));
    }

    /**
     * Finish {@code uuid}'s trip at {@code (x, z)} and return how far that is from where they came
     * in, or empty when they were not on a trip we saw the start of.
     *
     * <p>Horizontal only: every copy of a room stands in the same Y lane
     * ({@link PortalTwinLanes}), so a vertical term would contribute nothing but the corridor's own
     * floor-to-floor slop.</p>
     */
    public static OptionalDouble noteExited(UUID uuid, double x, double z) {
        Trip trip = TRIPS.remove(uuid);
        if (trip == null) return OptionalDouble.empty();
        return OptionalDouble.of(horizontalDistance(trip.x(), trip.z(), x, z));
    }

    /**
     * Advance or reset {@code uuid}'s dwell, returning the new count.
     *
     * <p>Counts consecutive scans, so stepping back into a corridor pauses the count and leaving the
     * structure altogether clears it — a player cannot accumulate five seconds a moment at a time
     * while walking back and forth through the door. Held at {@link #DWELL_SCANS} once reached
     * rather than climbing forever, so a long stay cannot overflow the counter.</p>
     */
    public static int tickDwell(UUID uuid, boolean inRoomBody) {
        if (!inRoomBody) {
            DWELL.remove(uuid);
            return 0;
        }
        int next = Math.min(DWELL_SCANS, DWELL.getOrDefault(uuid, 0) + 1);
        DWELL.put(uuid, next);
        return next;
    }

    /**
     * {@code uuid}'s current dwell count without advancing it — 0 when they are not in a room body.
     *
     * <p>Exists so a caller can see the <em>edge</em>: {@link #tickDwell} clamps at
     * {@link #DWELL_SCANS}, so "arrived just now" is a dwell that was below the threshold before the
     * tick and at it afterwards. {@link #dwellSatisfied} alone is true on every later scan too, which
     * is right for an advancement (vanilla dedupes) and wrong for anything that happens each time.</p>
     */
    public static int dwell(UUID uuid) {
        return DWELL.getOrDefault(uuid, 0);
    }

    /** True once a dwell count means the player has been in the room body long enough. */
    public static boolean dwellSatisfied(int scans) {
        return scans >= DWELL_SCANS;
    }

    /** True when a trip's displacement means the player left by a way out that is not the way in. */
    public static boolean isDistantExit(double metres) {
        return metres >= EXIT_DISTANCE_BLOCKS;
    }

    /** Horizontal distance between two points, the measure both questions are asked in. */
    public static double horizontalDistance(double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Drop everything remembered about {@code uuid} — on logout, and in tests. */
    public static void forget(UUID uuid) {
        TRIPS.remove(uuid);
        DWELL.remove(uuid);
    }
}
