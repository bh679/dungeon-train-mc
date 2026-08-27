package games.brennan.dungeontrain.client;

/**
 * Client-side mirror of everything the F3+4 Dungeon Train debug panel draws, plus the
 * permission that decides whether the panel can be opened at all.
 *
 * <p>The panel is a support tool, not a player feature: it is closed to everyone by default and
 * opens only while the player holds a live, time-boxed grant issued by the dev (see
 * {@link games.brennan.dungeontrain.debug.DebugAccessGrants}). {@link #permitted} therefore
 * defaults to {@code false} and is only ever set by
 * {@link games.brennan.dungeontrain.net.TrainDebugSyncPacket} — a client can't talk itself into
 * access, and an ungranted client is never even sent the world's generation seed.</p>
 *
 * <p>Fields are mutated on the client main thread from packet handlers and read on the same
 * thread during HUD rendering; {@code volatile} for safe publication, matching
 * {@link DebugFlagsState}. Nothing here is persisted — {@link #visible} starts false every
 * session, like vanilla's own F3 screen.</p>
 */
public final class TrainDebugState {

    /** Server-granted access. False until a {@code TrainDebugSyncPacket} says otherwise. */
    private static volatile boolean permitted = false;
    /** Epoch millis the grant lapses at; {@code 0} = never expires. Meaningless when !permitted. */
    private static volatile long expiresAtMs = 0L;
    /** Whether the player has toggled the panel on this session. */
    private static volatile boolean visible = false;

    private static volatile long seed = 0L;
    private static volatile boolean carriagePresent = false;
    private static volatile int pIdx = 0;
    private static volatile String variantId = "";

    private TrainDebugState() {}

    public static boolean permitted() {
        return permitted;
    }

    public static long expiresAtMs() {
        return expiresAtMs;
    }

    public static boolean visible() {
        return visible;
    }

    public static long seed() {
        return seed;
    }

    public static boolean carriagePresent() {
        return carriagePresent;
    }

    public static int pIdx() {
        return pIdx;
    }

    /** The variant the player's carriage index rolls to, or {@code ""} when unknown / off-train. */
    public static String variantId() {
        return variantId;
    }

    /**
     * Flip the panel. A no-op without permission, so an ungranted player pressing F3+4 gets no
     * panel and no hint that the chord exists.
     */
    public static void toggleVisible() {
        if (!permitted) return;
        visible = !visible;
    }

    /**
     * Apply a server permission sync. Losing permission closes the panel underneath the player —
     * a lapsed grant must not leave the last frame's data on screen.
     */
    public static void applyPermission(boolean granted, long grantExpiresAtMs, long trainSeed) {
        permitted = granted;
        expiresAtMs = granted ? grantExpiresAtMs : 0L;
        seed = granted ? trainSeed : 0L;
        if (!granted) {
            visible = false;
        }
    }

    /** Fed by {@code CarriageIndexPacket.handle} on every carriage-boundary crossing. */
    public static void setCarriage(boolean present, int carriagePIdx, String carriageVariantId) {
        carriagePresent = present;
        pIdx = present ? carriagePIdx : 0;
        variantId = present && carriageVariantId != null ? carriageVariantId : "";
    }

    /**
     * Drop everything on disconnect. The statics outlive a disconnect, so without this a player
     * who was granted on one server would arrive at the next one still holding access.
     */
    public static void reset() {
        permitted = false;
        expiresAtMs = 0L;
        visible = false;
        seed = 0L;
        carriagePresent = false;
        pIdx = 0;
        variantId = "";
    }
}
