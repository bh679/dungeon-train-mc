package games.brennan.dungeontrain.client.portal;

/**
 * What the renderer actually did on each frame of a portal arrival.
 *
 * <h2>Why this exists</h2>
 * <p>Three fixes now claim to make the arrival frame draw the destination, and the swap still
 * flashes. Reading the code has run out of answers because every one of them fails silently: the
 * mixins carry {@code require = 0}, so one that never attached looks exactly like one that works, and
 * a frustum re-derive that yields an empty list looks exactly like one that was never called.</p>
 *
 * <p>So the arrival gets measured instead. Four numbers per frame separate every remaining
 * hypothesis:</p>
 * <ul>
 *   <li><b>forced</b> — did {@code LevelRendererFrustumRefreshMixin} run and force the re-derive? Never
 *       true means that mixin is not applying, and the whole last commit is a no-op.</li>
 *   <li><b>visible</b> — how many sections {@code visibleSections} holds after {@code setupRender}.
 *       This is the number that decides it. Healthy is hundreds. Zero, or a handful, on a frame where
 *       {@code forced} was true means the re-derive happened and came back empty.</li>
 *   <li><b>sealed</b> — whether DT's own seal cut is in force. {@code addSectionsInFrustum} filters the
 *       graph through {@code Frustum.isVisible}, which {@code FrustumPortalSealMixin} hooks — so an
 *       empty list with the seal engaged points at the seal, and an empty list without it points at
 *       the occlusion graph still being the one walked from the old camera.</li>
 *   <li><b>the graph wait</b> — whether there was a rebuild to wait for at all, and how long it took.
 *       A wait that keeps timing out means 400 ms is simply not enough window.</li>
 * </ul>
 *
 * <p>Pure state, no imports: the two mixins that write it are on renderer classes and the reader is a
 * third. {@link #TRACE} turns the whole thing off without removing it, because this will be wanted
 * again the next time an arrival misbehaves.</p>
 */
public final class PortalArrivalTrace {

    /** Whether to say what each arrival frame did. Off ships; on diagnoses. */
    public static final boolean TRACE = false;

    /** Set by the frustum mixin when it forced a re-derive this frame; read and cleared by the trace. */
    private static volatile boolean forced;

    /** Frames traced since this arrival began, so a long window cannot flood the log. */
    private static volatile int frames;

    /** Most frames worth reporting for one arrival — a third of a second at 60fps. */
    private static final int MAX_FRAMES = 20;

    private PortalArrivalTrace() {}

    /** The frustum hook fired and forced a re-derive on this frame. */
    public static void noteForced() {
        forced = true;
    }

    /** Whether this frame's re-derive was forced by us, clearing it for the next frame. */
    public static boolean consumeForced() {
        boolean was = forced;
        forced = false;
        return was;
    }

    /** Whether this arrival has any trace budget left, spending one frame of it. */
    public static boolean claimFrame() {
        if (!TRACE || frames >= MAX_FRAMES) return false;
        frames++;
        return true;
    }

    /** A new arrival: start counting frames again. Called when a swap arms the window. */
    public static void beginArrival() {
        frames = 0;
        forced = false;
    }
}
