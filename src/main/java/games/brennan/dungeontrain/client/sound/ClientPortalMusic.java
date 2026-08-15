package games.brennan.dungeontrain.client.sound;

import games.brennan.dungeontrain.net.PortalTrainAudioPacket;

/**
 * How loud the soundtrack should be for a position inside a portal structure — the music
 * counterpart to {@link ClientPortalTrainAudio}, reading the same cached region.
 *
 * <p><b>The rule.</b> A dimensional carriage is somewhere else. Walking the corridor towards it, the music
 * fades out over the last {@link #MUSIC_FADE_BLOCKS} blocks and is gone by the doorway; in the room
 * and every copy of it there is none. Measured as distance to the corridor's <i>room-facing</i>
 * door, which {@code PortalStructure}'s layout — {@code [plug] [entry twin] [room] [exit twin]
 * [plug]} along +X — fixes without anyone having to know which way the player is walking or which
 * way the train runs: it is the entry corridor's high-X end and the exit corridor's low-X end. So
 * the two mouths of the room behave identically, and walking back out of one brings the music
 * region back up the same way it went down.</p>
 *
 * <p><b>Only the twin half of the walk is covered, and that is enough.</b> The first half of a
 * portal corridor is the carriage on the train; crossing its midpoint swaps the player into the
 * twin copy stamped under the bedrock, which is the only part the server describes to the client.
 * A player therefore enters the region at roughly the corridor's midpoint — a little past it, since
 * {@code PortalFrames.SWAP_HYSTERESIS} lets the swap land over a block late — so an eight-block
 * band starts about where they arrive, at or near full volume.</p>
 *
 * <p><b>Eased, unlike the engine volume.</b> That swap is a teleport: the position the factor is
 * computed from moves a long way between two ticks, and the hysteresis means the target can step
 * down on arrival rather than starting clean at {@code 1}. Easing the applied value absorbs both,
 * and costs nothing on the ordinary walk, where the target moves slower than the ease does.</p>
 *
 * <p>Pure logic, no Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class ClientPortalMusic {

    /**
     * How many blocks before the room-facing door the music takes to reach silence.
     *
     * <p>Clamped to the corridor's own length, so a world configured with carriages shorter than
     * this fades over the whole corridor rather than starting the band outside it.</p>
     */
    public static final double MUSIC_FADE_BLOCKS = 8.0;

    /** Containment slack in blocks, matching {@link ClientPortalTrainAudio}. */
    private static final double PAD = 0.5;

    /** Fraction of the remaining gap closed each tick. Converges in well under half a second. */
    private static final float EASE_PER_TICK = 0.2f;

    /** Below this the ease snaps, so the factor reaches a clean {@code 0} and a clean {@code 1}. */
    private static final float SNAP_EPSILON = 0.005f;

    private static volatile float current = 1.0f;

    private ClientPortalMusic() {}

    /**
     * The factor the currently applied ease has reached — what the volume mixin scales {@code MUSIC}
     * by. {@code 1} means "nothing to say".
     */
    public static float current() {
        return current;
    }

    /** Advance the ease one tick towards {@code target} and return the new value. */
    public static float tick(float target) {
        float from = current;
        float next = from + (target - from) * EASE_PER_TICK;
        if (Math.abs(target - next) <= SNAP_EPSILON) next = target;
        current = next;
        return next;
    }

    /** Forget everything. Wired to logging out, alongside the region cache's own reset. */
    public static void reset() {
        current = 1.0f;
    }

    /** {@link #targetFactor(PortalTrainAudioPacket, double, double, double)} against the live cache. */
    public static float targetFactorAt(double x, double y, double z) {
        return targetFactor(ClientPortalTrainAudio.region(), x, y, z);
    }

    /**
     * The music volume this position demands — {@code 1} outside any portal structure, ramping down
     * to {@code 0} over the last {@link #MUSIC_FADE_BLOCKS} blocks of a corridor before its
     * room-facing door, and {@code 0} everywhere else inside the structure.
     */
    public static float targetFactor(PortalTrainAudioPacket r, double x, double y, double z) {
        if (r == null || !r.applies()) return 1.0f;
        if (!withinStructure(r, x, y, z)) return 1.0f;

        double span = Math.min(MUSIC_FADE_BLOCKS, r.length());

        // The entry corridor opens onto the room at its high-X end; the exit corridor at its low-X
        // end. A position can only be in one of them — they are a room apart.
        if (withinCorridor(r, r.entryX(), r.entryY(), r.entryZ(), x, y, z)) {
            return ramp((r.entryX() + r.length() + PAD) - x, span);
        }
        if (withinCorridor(r, r.exitX(), r.exitY(), r.exitZ(), x, y, z)) {
            return ramp(x - (r.exitX() - PAD), span);
        }
        // The room, and every tiled copy of it.
        return 0.0f;
    }

    /** {@code 0} at the door, {@code 1} once {@code span} blocks back from it. */
    private static float ramp(double distanceToDoor, double span) {
        if (distanceToDoor <= 0.0) return 0.0f;
        if (distanceToDoor >= span) return 1.0f;
        return (float) (distanceToDoor / span);
    }

    /** True when the position is anywhere in the structure — corridors, room, and every copy of it. */
    private static boolean withinStructure(PortalTrainAudioPacket r, double x, double y, double z) {
        return x >= r.minX() && x <= r.maxX()
            && y >= r.minY() && y <= r.maxY()
            && z >= r.minZ() && z <= r.maxZ();
    }

    /** True inside a corridor's padded box, which spans {@code length × height × width}. */
    private static boolean withinCorridor(PortalTrainAudioPacket r, int ox, int oy, int oz,
                                          double x, double y, double z) {
        return x >= ox - PAD && x <= ox + r.length() + PAD
            && y >= oy - PAD && y <= oy + r.height() + PAD
            && z >= oz - PAD && z <= oz + r.width() + PAD;
    }
}
