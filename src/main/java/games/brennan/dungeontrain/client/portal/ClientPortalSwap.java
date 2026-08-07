package games.brennan.dungeontrain.client.portal;

/**
 * A one-shot "the server just swapped you between a portal corridor and its twin" flag, read by
 * {@code SectionOcclusionGraphPortalSwapMixin} on the next frame.
 *
 * <p><b>Why the renderer needs telling.</b> A swap is a hundred-block jump in Y, and vanilla treats a
 * camera move of eight blocks or more as reason to rebuild the section occlusion graph — but it does
 * that rebuild on {@code Util.backgroundExecutor()} and keeps drawing from the old graph until it
 * lands ({@code SectionOcclusionGraph.update} → {@code scheduleFullUpdate}). The old graph was walked
 * from inside the train, and the twin is sealed under the bedrock where nothing could see into it, so
 * its sections are not in that set. Until the rebuild finishes, nothing around the player is drawn at
 * all and the screen is left as the clear colour — which underground in a lit overworld is pale
 * enough to read as a white flash. How many frames that lasts depends on when a background thread
 * gets to it, which is why it only happened <i>sometimes</i>.</p>
 *
 * <p><b>A flag rather than a measurement.</b> The client could watch its own position and infer a big
 * jump, and that would cover teleports this mod knows nothing about — but it would also fire on
 * respawns, {@code /tp}, and anything else, and the cost here is a blocking wait. Being told is
 * narrower and cannot misfire.</p>
 *
 * <p>Pure logic, no rendering or loader imports, because {@code PortalSwapPacket} names it from the
 * common {@code net} package — the same reason {@code ClientPortalRoomFog} keeps its renderer talk in
 * {@code PortalRoomFogEvents}. Nothing has to clear this on the way out of a world: the flag expires
 * on its own, and the worst a stale one could do is make a single frame wait for a rebuild that was
 * already happening.</p>
 */
public final class ClientPortalSwap {

    /**
     * How long an armed flag stays worth acting on.
     *
     * <p>It is meant to be consumed by the very next frame — the jump guarantees the invalidate, and
     * the invalidate guarantees the rebuild. This only covers the case where it is not: a frame that
     * never came because the game was paused, or a swap that somehow moved the camera less than the
     * eight blocks vanilla needs to care about. Generous enough to survive a slow frame, short enough
     * that nobody feels it.</p>
     */
    private static final long TTL_NANOS = 250_000_000L;

    /** {@link System#nanoTime()} the flag was armed at, or {@link Long#MIN_VALUE} for "not armed". */
    private static volatile long armedAt = Long.MIN_VALUE;

    private ClientPortalSwap() {}

    /** Called from the packet handler: the next frame should wait for a fresh occlusion graph. */
    public static void arm() {
        armedAt = System.nanoTime();
    }

    /** True once per swap, for the frame that should do the waiting. Clears the flag either way. */
    public static boolean consume() {
        long at = armedAt;
        if (at == Long.MIN_VALUE) return false;
        armedAt = Long.MIN_VALUE;
        return System.nanoTime() - at <= TTL_NANOS;
    }

    /** Forget an armed swap outright. */
    public static void reset() {
        armedAt = Long.MIN_VALUE;
    }
}
