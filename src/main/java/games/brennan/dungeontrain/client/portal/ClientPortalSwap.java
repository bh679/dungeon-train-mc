package games.brennan.dungeontrain.client.portal;

/**
 * A one-shot "the server just swapped you between a portal corridor and its twin" token, read by the
 * two renderer mixins that make the arrival frame draw the destination instead of the sky.
 *
 * <h2>Why the renderer needs telling</h2>
 * <p>A swap is a hundred-block jump in Y, and two separate pieces of vanilla assume a camera gets
 * where it is going gradually:</p>
 * <ol>
 *   <li><b>The occlusion graph.</b> A camera move of eight blocks or more invalidates it, but the
 *       rebuild runs on {@code Util.backgroundExecutor()} and drawing continues from the old graph
 *       until it lands. The old graph was walked from inside the train, and the twin is sealed under
 *       bedrock where nothing could see into it, so its sections are not in that set — nothing around
 *       the player is drawn at all. {@code SectionOcclusionGraphPortalSwapMixin} waits it out.</li>
 *   <li><b>The section meshes.</b> Being in the graph is not being built: a section is only ever
 *       compiled once it has been visible, and the twin's never have. So the first fix hands the
 *       renderer a correct list of sections that have nothing in them yet, and the arrival frame draws
 *       the skybox through them. {@code LevelRendererPortalSwapCompileMixin} borrows vanilla's own
 *       "build the nearby ones synchronously" path for that one frame.</li>
 * </ol>
 * <p>The second only showed up once the first was fixed, and only in one direction — going back to the
 * train needs no rebuild, because riding it kept those sections compiled all along.</p>
 *
 * <h2>Shape</h2>
 * <p><b>A token rather than a measurement.</b> The client could watch its own position and infer a big
 * jump, which would cover teleports this mod knows nothing about — but it would also fire on respawns,
 * {@code /tp} and everything else, and both fixes cost blocking work. Being told is narrower and
 * cannot misfire.</p>
 *
 * <p>Everything here is bounded by {@link #TTL_NANOS}, so no stuck flag can leave a client permanently
 * doing synchronous chunk builds — the worst case is that it stops helping. Pure logic, no rendering or
 * loader imports, because {@code PortalSwapPacket} names it from the common {@code net} package.</p>
 */
public final class ClientPortalSwap {

    /**
     * How long an armed token stays worth acting on.
     *
     * <p>Both readers are meant to fire on the very next frame. This covers the cases where they do
     * not — a frame that never came because the game was paused, a swap too small for vanilla to
     * invalidate anything, or a mixin that did not apply — and it is what stops any of this outliving
     * the swap it belongs to.</p>
     */
    private static final long TTL_NANOS = 250_000_000L;

    /** {@link System#nanoTime()} the token was armed at, or {@link Long#MIN_VALUE} for "not armed". */
    private static volatile long armedAt = Long.MIN_VALUE;

    /** Whether this swap's occlusion-graph wait has already happened. */
    private static volatile boolean graphAwaited = true;

    /** Whether this swap's frame of synchronous nearby section builds has already happened. */
    private static volatile boolean nearbyCompiled = true;

    /** Whether the one-shot "this is working" trace has been emitted yet. */
    private static volatile boolean traced = false;

    private ClientPortalSwap() {}

    /** Called from the packet handler: the next frame is an arrival and needs both fixes. */
    public static void arm() {
        graphAwaited = false;
        nearbyCompiled = false;
        armedAt = System.nanoTime();
    }

    /** Forget an armed swap outright. */
    public static void reset() {
        armedAt = Long.MIN_VALUE;
        graphAwaited = true;
        nearbyCompiled = true;
    }

    /** True for the one frame that should wait out the occlusion-graph rebuild. */
    public static boolean claimGraphWait() {
        if (graphAwaited || !live()) return false;
        graphAwaited = true;
        return true;
    }

    /**
     * Whether this frame should build the sections around the camera synchronously.
     *
     * <p>Read rather than claimed, because vanilla asks the same question once per dirty section
     * inside one pass. {@link #finishNearbyCompile} is what ends the frame's worth of it.</p>
     */
    public static boolean wantsNearbyCompile() {
        return !nearbyCompiled && live();
    }

    /** One frame of synchronous nearby builds is enough — stop after this pass. */
    public static void finishNearbyCompile() {
        nearbyCompiled = true;
    }

    /**
     * True exactly once per game session, for the first swap the renderer acts on.
     *
     * <p>Both mixins carry {@code require = 0}, so a missed injection is silent by design — and a
     * renderer that never helps looks exactly like one that helps perfectly, since both draw a clean
     * frame most of the time. So the first one says so out loud, for the same reason the swap itself is
     * logged server-side.</p>
     */
    public static boolean claimFirstTrace() {
        if (traced) return false;
        traced = true;
        return true;
    }

    private static boolean live() {
        long at = armedAt;
        return at != Long.MIN_VALUE && System.nanoTime() - at <= TTL_NANOS;
    }
}
