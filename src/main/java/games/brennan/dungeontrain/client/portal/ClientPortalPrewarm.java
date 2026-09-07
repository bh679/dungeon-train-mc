package games.brennan.dungeontrain.client.portal;

import java.util.Arrays;

/**
 * Where the player walking down a portal corridor is about to be put, so the renderer can build that
 * place <em>before</em> it has to draw it.
 *
 * <h2>Why arriving is too late</h2>
 * <p>{@link ClientPortalSwap} repairs the arrival frame, and it is still the right thing to do — but
 * both halves of it are emergency work done after the camera has already moved, and the second half
 * is narrow by construction. Vanilla's {@code LevelRenderer.compileSections} only ever looks at
 * sections that are <i>already in {@code visibleSections}</i>, and the {@code NEARBY} priority the
 * swap borrows only builds those within about 28 blocks of the camera. Everything the freshly
 * rebuilt occlusion graph adds beyond that ring — and, for an exit copy, a destination in different
 * chunk columns altogether — is queued for a background build and draws as clear colour until it
 * lands. That is the flash that survives both fixes.</p>
 *
 * <p>Nothing about that is fixable at arrival: the work simply takes longer than a frame. So it is
 * moved earlier instead. A player is inside a corridor for a second or two before the swap fires,
 * the server knows the whole time where their counterpart position is
 * ({@code PortalFrames.mirror}), and a background chunk build spread across that walk costs nothing
 * anybody can see.</p>
 *
 * <h2>Shape</h2>
 * <p>This holds the decisions — which sections, in what order, how many per tick — and no Minecraft
 * types at all, so it unit-tests without a NeoForge bootstrap and can be named from the common
 * {@code net} package the way {@link ClientPortalSwap} is. {@code PortalPrewarmTicker} is the half
 * that talks to the renderer.</p>
 *
 * <p><b>Best-effort, and bounded twice over.</b> A section whose chunk has not arrived is simply not
 * built and comes round again on the next pass; the whole token expires on {@link #TTL_NANOS} if the
 * server stops talking. The worst case for a client is that it stops helping.</p>
 */
public final class ClientPortalPrewarm {

    /**
     * How far around the destination is worth building, in sections, horizontally.
     *
     * <p>Two — 40 blocks either side — because the destination is not always a nine-block corridor.
     * A portal room can be 64 blocks across, and what has to be on screen at arrival is whatever the
     * landing point can see, which in a sealed room is the whole of it.</p>
     */
    static final int RADIUS_SECTIONS_XZ = 2;

    /**
     * The same vertically, and smaller: a corridor and its room share one floor, so the interesting
     * geometry is a slab rather than a cube, and every section spent on rock above or below is a
     * section not spent on what the player will be looking at.
     */
    static final int RADIUS_SECTIONS_Y = 1;

    /**
     * Sections built per client tick — built, not queued: the ticker compiles them synchronously on
     * the render thread, the way vanilla's NEARBY setting does for sections beside the camera.
     *
     * <p>Three, because each is on the order of a millisecond and this runs while the player is
     * walking, so it has to stay inside a frame's spare time. It still walks the whole
     * {@link #RADIUS_SECTIONS_XZ} × {@link #RADIUS_SECTIONS_Y} span in about a second, which is
     * inside the walk down a corridor, and most of the span costs a field read: only a dirty section
     * is built.</p>
     */
    static final int SECTIONS_PER_TICK = 3;

    /**
     * How long an armed destination stays worth acting on.
     *
     * <p>Generously longer than the server's re-send period, so an ordinary dropped packet or a tick
     * the server spent elsewhere does not stop a prewarm mid-pass. It is the whole of the
     * stale-state defence — a player who walks out of a corridor is never told so, because the
     * silence is what expires it.</p>
     */
    private static final long TTL_NANOS = 10_000_000_000L;

    /** {@link System#nanoTime()} the destination was last restated at, or {@link Long#MIN_VALUE}. */
    private static volatile long armedAt = Long.MIN_VALUE;

    /** The destination's own section, packed, or {@link Long#MIN_VALUE} for "none". */
    private static volatile long targetSection = Long.MIN_VALUE;

    /** The span around {@link #targetSection}, nearest first. Client thread only. */
    private static long[] pending = new long[0];

    /** How far through {@link #pending} the last claim got. Client thread only. */
    private static int cursor = 0;

    /** Whether the one-shot "this is working" trace has been emitted yet. */
    private static volatile boolean traced = false;

    private ClientPortalPrewarm() {}

    /**
     * The server says this player would land here if they crossed now.
     *
     * <p>Restating the same destination refreshes the clock and leaves the pass where it is, which
     * is what a player standing still in a corridor does; a different one starts the span again.</p>
     */
    public static void arm(int x, int y, int z) {
        long section = packSection(x >> 4, y >> 4, z >> 4);
        if (section != targetSection) {
            targetSection = section;
            pending = spanAround(section);
            cursor = 0;
        }
        armedAt = System.nanoTime();
    }

    /** Forget the destination outright. Wired to logging out, like every other portal client cache. */
    public static void reset() {
        armedAt = Long.MIN_VALUE;
        targetSection = Long.MIN_VALUE;
        pending = new long[0];
        cursor = 0;
    }

    /** Whether there is a live destination worth building around. */
    public static boolean live() {
        long at = armedAt;
        return at != Long.MIN_VALUE && targetSection != Long.MIN_VALUE
            && System.nanoTime() - at <= TTL_NANOS;
    }

    /**
     * The next few sections to try, packed, or an empty array when nothing is armed.
     *
     * <p><b>Cycles rather than remembering what it built.</b> A section that is already built is not
     * dirty, and the ticker drops it for the cost of one field read — so a second pass over the span
     * is cheaper than the bookkeeping needed to avoid it, and it is also what picks up the chunks
     * that had not arrived the first time round.</p>
     */
    public static long[] claim() {
        if (!live() || pending.length == 0) return new long[0];

        int take = Math.min(SECTIONS_PER_TICK, pending.length);
        long[] batch = new long[take];
        for (int i = 0; i < take; i++) {
            batch[i] = pending[cursor];
            cursor = (cursor + 1) % pending.length;
        }
        return batch;
    }

    /**
     * Every section of the live destination's span, nearest first — or nothing when none is live.
     *
     * <p>For the arrival, which wants all of them at once rather than a tick's worth: the room a
     * player has just landed in is listed for drawing straight from this, without waiting for the
     * occlusion graph to find it. The array is shared, not copied, and must not be written to.</p>
     */
    public static long[] span() {
        return live() ? pending : new long[0];
    }

    /**
     * True exactly once per game session, for the first section a prewarm actually queues.
     *
     * <p>Same reason {@link ClientPortalSwap#claimFirstTrace} exists: the accessors this rides on can
     * fail to apply silently, and a prewarm that never runs looks exactly like one that works — both
     * of them draw a clean frame most of the time.</p>
     */
    public static boolean claimFirstTrace() {
        if (traced) return false;
        traced = true;
        return true;
    }

    /** The sections within the radii of {@code centre}, nearest first. */
    static long[] spanAround(long centre) {
        int cx = sectionX(centre), cy = sectionY(centre), cz = sectionZ(centre);
        int width = RADIUS_SECTIONS_XZ * 2 + 1;
        int height = RADIUS_SECTIONS_Y * 2 + 1;

        long[] span = new long[width * height * width];
        int[] order = new int[span.length];
        int n = 0;
        for (int dx = -RADIUS_SECTIONS_XZ; dx <= RADIUS_SECTIONS_XZ; dx++) {
            for (int dy = -RADIUS_SECTIONS_Y; dy <= RADIUS_SECTIONS_Y; dy++) {
                for (int dz = -RADIUS_SECTIONS_XZ; dz <= RADIUS_SECTIONS_XZ; dz++) {
                    span[n] = packSection(cx + dx, cy + dy, cz + dz);
                    order[n] = dx * dx + dy * dy + dz * dz;
                    n++;
                }
            }
        }

        // A small insertion sort on the two arrays together: the span is a hundred-odd entries built
        // once per destination, and pairing them into objects to hand to a comparator would allocate
        // more than the sort saves.
        for (int i = 1; i < n; i++) {
            long section = span[i];
            int distance = order[i];
            int j = i - 1;
            while (j >= 0 && order[j] > distance) {
                span[j + 1] = span[j];
                order[j + 1] = order[j];
                j--;
            }
            span[j + 1] = section;
            order[j + 1] = distance;
        }
        return Arrays.copyOf(span, n);
    }

    /** Section coordinates into one long — the same shape as vanilla's, kept here to stay pure. */
    static long packSection(int x, int y, int z) {
        return ((long) (x & 0x3FFFFF) << 42) | ((long) (y & 0xFFFFF) << 22) | (z & 0x3FFFFFL);
    }

    public static int sectionX(long packed) {
        return (int) (packed >> 42);
    }

    public static int sectionY(long packed) {
        return (int) (packed << 22 >> 44);
    }

    public static int sectionZ(long packed) {
        return (int) (packed << 42 >> 42);
    }
}
