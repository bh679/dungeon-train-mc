package games.brennan.dungeontrain.client;

import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.Locale;

/**
 * What Dungeon Train <em>asked the frame for</em>, recorded so it can be read back on screen next
 * to what the frame actually looks like.
 *
 * <h2>Why this exists</h2>
 * <p>Under a shader pack an atmosphere effect can fail two entirely different ways that look
 * identical: Dungeon Train never asked for it (wrong position, band not synced, feature gated off),
 * or Dungeon Train asked and the pack discarded the request. A screenshot alone cannot tell those
 * apart, and every fix for the second is wrong for the first. So each hook records the value it
 * applied, {@link ShaderDiagnosticsHud} draws those values into the same screenshot, and the pair
 * is a self-contained record: the panel is the ask, the image is the answer.</p>
 *
 * <h2>Freshness</h2>
 * <p>The sky and fog fields are <b>per-frame</b>: the HUD clears them in {@link #consumeFrame()}
 * after it has drawn, so a hook that does not run next frame leaves its field at the "nothing was
 * asked for" default rather than showing last frame's value. That distinction is the entire point,
 * so it is not left to chance.</p>
 *
 * <p>The lightmap fields are deliberately <em>not</em> per-frame. {@code LightTexture} rebuilds on
 * a tick flag rather than every frame, so clearing them per frame would blank them on most frames;
 * they hold an eased value that is meaningful until it is next written.</p>
 *
 * <p>Recording is gated on {@link #recording()} so an ordinary frame pays nothing: the panel is
 * closed to everyone without a live debug grant, exactly like the F3+4 train panel.</p>
 */
public final class ShaderDiagnostics {

    /** Whether the panel is toggled on. Recording follows this, so it is free when closed. */
    private static volatile boolean visible = false;

    // --- Per-frame: band sky overlays, alpha as actually drawn (0 = did not draw) ---------------
    private static volatile float skyVoid;
    private static volatile float skyNether;
    private static volatile float skyUpsideDown;

    // --- Per-frame: fog colour (ViewportEvent.ComputeFogColor) ---------------------------------
    private static volatile String fogColorSource = "";
    private static volatile int fogColorIn;
    private static volatile int fogColorOut;

    // --- Per-frame: fog distance (ViewportEvent.RenderFog) --------------------------------------
    private static volatile boolean fogDistanceAsked;
    private static volatile float fogVanillaFar;
    private static volatile float fogFar;
    private static volatile float fogNear;
    private static volatile boolean fogCancelled;

    // --- Per-frame: clouds ------------------------------------------------------------------------
    private static volatile boolean cloudsHookRan;
    private static volatile boolean cloudsCancelled;
    private static volatile float cloudHeightVanilla;
    private static volatile float cloudHeightApplied;

    // --- Per-frame: skybox blocks ----------------------------------------------------------------
    private static volatile int skyboxCubes;
    private static volatile String skyboxVariants = "";
    private static volatile boolean skyboxStencil;
    private static volatile boolean skyboxDrew;

    // --- Sticky: what the level framebuffer's stencil attachment was, last time the punch looked --
    private static volatile String levelFboStencil = "";

    // --- Lightmap-paced: dimensional carriage sky/lighting and the transition --------------------
    private static volatile String roomSkyKind = "";
    private static volatile float roomSkyT;
    private static volatile float roomSkyLift;
    private static volatile float crossingT;

    private ShaderDiagnostics() {}

    /** Whether the hooks should bother recording. */
    public static boolean recording() {
        return visible;
    }

    public static boolean visible() {
        return visible;
    }

    /**
     * Flip the panel. A no-op without a live debug grant, so an ungranted player pressing the chord
     * gets no panel and no hint that it exists — the same contract as {@link TrainDebugState}.
     * Opening clears the per-frame fields so the first frame shown is measured, not inherited.
     */
    public static void toggleVisible() {
        if (!TrainDebugState.permitted()) return;
        visible = !visible;
        if (visible) clearFrame();
    }

    /**
     * The last per-frame record that was actually drawn into a frame.
     *
     * <p>Exists because the panel and the log read at different moments. {@link #consumeFrame()}
     * clears the live fields as soon as the HUD has drawn them, but anything reading on a
     * <em>tick</em> — the sweep's log line — runs after that clear and would record zeros for a
     * frame that plainly showed otherwise. Keeping the values the HUD last drew means the log and
     * the screenshot agree, which is the whole basis for trusting either.</p>
     */
    private static volatile Frame lastDrawn = Frame.EMPTY;

    /** The per-frame values as last drawn — what the newest screenshot actually shows. */
    public static Frame lastDrawn() {
        return lastDrawn;
    }

    /** Clear the per-frame record, keeping a copy of what was drawn. Called by the HUD each frame. */
    public static void consumeFrame() {
        lastDrawn = new Frame(skyVoid, skyNether, skyUpsideDown,
            fogColorSource, fogColorIn, fogColorOut,
            fogDistanceAsked, fogVanillaFar, fogFar, fogNear, fogCancelled,
            skyboxCubes, skyboxVariants, skyboxStencil, skyboxDrew,
            cloudsHookRan, cloudsCancelled, cloudHeightVanilla, cloudHeightApplied);
        clearFrame();
    }

    private static void clearFrame() {
        skyVoid = 0.0f;
        skyNether = 0.0f;
        skyUpsideDown = 0.0f;
        fogColorSource = "";
        fogColorIn = 0;
        fogColorOut = 0;
        fogDistanceAsked = false;
        fogVanillaFar = 0.0f;
        fogFar = 0.0f;
        fogNear = 0.0f;
        fogCancelled = false;
        skyboxCubes = 0;
        skyboxVariants = "";
        skyboxStencil = false;
        skyboxDrew = false;
        cloudsHookRan = false;
        cloudsCancelled = false;
        cloudHeightVanilla = 0.0f;
        cloudHeightApplied = 0.0f;
    }

    /** Drop everything on disconnect, so one world's readings never colour the next. */
    public static void reset() {
        visible = false;
        clearFrame();
        lastDrawn = Frame.EMPTY;
        roomSkyKind = "";
        roomSkyT = 0.0f;
        roomSkyLift = 0.0f;
        crossingT = 0.0f;
    }

    // --- Recording -------------------------------------------------------------------------------

    /** One band sky overlay drew at {@code alpha}. Called from the three {@code *SkyRenderer}s. */
    public static void recordBandSky(BandSky which, float alpha) {
        switch (which) {
            case VOID -> skyVoid = alpha;
            case NETHER -> skyNether = alpha;
            case UPSIDE_DOWN -> skyUpsideDown = alpha;
        }
    }

    /** A band shifted the fog colour. {@code in}/{@code out} are packed 0xRRGGBB. */
    public static void recordFogColor(String source, int in, int out) {
        fogColorSource = source;
        fogColorIn = in;
        fogColorOut = out;
    }

    /**
     * A fog-distance decision was reached, including the case where the room declined to shrink
     * anything. Recording the declines is what separates "the pack ignored us" from "we never
     * asked" — see {@code PortalRoomFogEvents}, which returns early on two distinct paths.
     */
    public static void recordFogDistance(float vanillaFar, float far, float near, boolean cancelled) {
        fogDistanceAsked = true;
        fogVanillaFar = vanillaFar;
        fogFar = far;
        fogNear = near;
        fogCancelled = cancelled;
    }

    /**
     * The stencil attachment type of the framebuffer the level was rendering into, as read at
     * {@code AFTER_SKY}. Sticky rather than per-frame: it is a property of the pipeline, not of
     * the frame, and the panel is more useful showing the last reading than a blank.
     */
    public static void recordLevelFboStencil(String type) {
        levelFboStencil = type == null ? "" : type;
    }

    /** The skybox punch pass' outcome for this frame. */
    public static void recordSkybox(int cubes, String variants, boolean stencil, boolean drew) {
        skyboxCubes = cubes;
        skyboxVariants = variants == null ? "" : variants;
        skyboxStencil = stencil;
        skyboxDrew = drew;
    }

    /**
     * Vanilla's {@code renderClouds} reached DT's hook this frame, and whether DT cancelled it.
     *
     * <p><b>Whether the hook ran at all is the measurement.</b> DT hides the clouds over the End and
     * Nether bands, and sinks them to the floor in the upside-down band, entirely through vanilla's
     * cloud pass. A pack that draws its own clouds in composite never calls that pass — so
     * {@code cloudsHookRan == false} means both behaviours are dead for that pack, whatever the
     * frame happens to look like.</p>
     */
    public static void recordCloudsHook(boolean cancelled) {
        cloudsHookRan = true;
        cloudsCancelled = cancelled;
    }

    /** The cloud plane height vanilla asked for, and what DT redirected it to. */
    public static void recordCloudHeight(float vanilla, float applied) {
        cloudHeightVanilla = vanilla;
        cloudHeightApplied = applied;
    }

    /** The dimensional-carriage sky lift applied on this lightmap rebuild. */
    public static void recordRoomSky(String kind, float t, float lift) {
        roomSkyKind = kind == null ? "" : kind;
        roomSkyT = t;
        roomSkyLift = lift;
    }

    /** The portal-corridor lightmap hold applied on this rebuild. */
    public static void recordCrossing(float t) {
        crossingT = t;
    }

    // --- Read-back ---------------------------------------------------------------------------------

    public static float skyVoid() { return skyVoid; }
    public static float skyNether() { return skyNether; }
    public static float skyUpsideDown() { return skyUpsideDown; }

    public static String fogColorSource() { return fogColorSource; }
    public static int fogColorIn() { return fogColorIn; }
    public static int fogColorOut() { return fogColorOut; }

    public static boolean fogDistanceAsked() { return fogDistanceAsked; }
    public static float fogVanillaFar() { return fogVanillaFar; }
    public static float fogFar() { return fogFar; }
    public static float fogNear() { return fogNear; }
    public static boolean fogCancelled() { return fogCancelled; }

    public static boolean cloudsHookRan() { return cloudsHookRan; }
    public static boolean cloudsCancelled() { return cloudsCancelled; }
    public static float cloudHeightVanilla() { return cloudHeightVanilla; }
    public static float cloudHeightApplied() { return cloudHeightApplied; }

    public static String levelFboStencil() { return levelFboStencil; }

    public static int skyboxCubes() { return skyboxCubes; }
    public static String skyboxVariants() { return skyboxVariants; }
    public static boolean skyboxStencil() { return skyboxStencil; }
    public static boolean skyboxDrew() { return skyboxDrew; }

    public static String roomSkyKind() { return roomSkyKind; }
    public static float roomSkyT() { return roomSkyT; }
    public static float roomSkyLift() { return roomSkyLift; }
    public static float crossingT() { return crossingT; }

    /** Format a 0..1 intensity for the panel. Three places is enough to see an ease move. */
    public static String fmt(float v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** Format a packed 0xRRGGBB colour for the panel. */
    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /**
     * The fog colour currently on a {@link ViewportEvent.ComputeFogColor}, packed 0xRRGGBB. Called
     * either side of a band's tint so the panel can show what it was handed and what it returned.
     */
    public static int packFog(ViewportEvent.ComputeFogColor event) {
        return (channel(event.getRed()) << 16) | (channel(event.getGreen()) << 8) | channel(event.getBlue());
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    /**
     * One frame's worth of per-frame asks, as drawn. A value type so a reader on another thread
     * (or another moment in the frame) sees one coherent set rather than a torn mixture.
     */
    public record Frame(float skyVoid, float skyNether, float skyUpsideDown,
                        String fogColorSource, int fogColorIn, int fogColorOut,
                        boolean fogDistanceAsked, float fogVanillaFar, float fogFar, float fogNear,
                        boolean fogCancelled,
                        int skyboxCubes, String skyboxVariants, boolean skyboxStencil, boolean skyboxDrew,
                        boolean cloudsHookRan, boolean cloudsCancelled,
                        float cloudHeightVanilla, float cloudHeightApplied) {

        public static final Frame EMPTY =
            new Frame(0, 0, 0, "", 0, 0, false, 0, 0, 0, false, 0, "", false, false,
                false, false, 0, 0);
    }

    /** Which band sky overlay a recording came from. */
    public enum BandSky { VOID, NETHER, UPSIDE_DOWN }
}
