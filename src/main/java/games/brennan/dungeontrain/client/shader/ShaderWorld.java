package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientPortalRoomSky;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.portal.PortalRoomSky;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Which of a shader pack's worlds Dungeon Train wants rendered right now, and how far along the
 * change is.
 *
 * <h2>The decision</h2>
 * <p>Three things can pull the frame away from the pack's overworld: the Nether band, the End
 * band (the void's sky ramp is the End's), and a dimensional carriage whose sky is Nether or End.
 * Each is a 0..1 ramp. A band's ramp is remapped through {@link #FADE_WINDOW_START}..{@link
 * #FADE_WINDOW_END} so the expensive double-render window is the middle of the band's own fade
 * rather than the whole of it; a room's ease is used as it stands, because it is already a short
 * walk or a one-second ease.</p>
 *
 * <p>The result is a {@link Blend}: {@code from}, {@code to} and a weight {@code w}. {@code w} at 0
 * or 1 is a settled world and one render; anything between is a cross-fade and two.</p>
 *
 * <h2>What Iris is told</h2>
 * <p>{@link #irisOverride()} answers {@code IrisCurrentDimensionMixin} from {@link #reporting},
 * which {@link ShaderWorldCrossfade} sets explicitly around each {@code renderLevel} call. It is
 * never derived per call: Iris asks several times a frame, from the pipeline lookup and from
 * uniform lambdas, and every answer within one render must agree.</p>
 *
 * <p>Everything Iris-typed is reached reflectively, once. Without Iris every method here is a
 * cheap no-op and the class never touches an Iris symbol.</p>
 */
public final class ShaderWorld {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A shader pack's world folders, as Iris names them. */
    public enum World {
        OVERWORLD("world0"), NETHER("world-1"), END("world1");

        private final String folder;

        World(String folder) {
            this.folder = folder;
        }

        public String folder() {
            return folder;
        }
    }

    /** {@code from} → {@code to} at {@code w}. {@code w} outside (0,1) is a settled {@code to}. */
    public record Blend(World from, World to, float w, World settled) {

        /** Unstabilised, for callers that have no history to draw on. Used by the "no pack" path. */
        Blend(World from, World to, float w) {
            this(from, to, w, w >= 0.5f ? to : from);
        }

        public boolean fading() {
            return w > 0.0f && w < 1.0f;
        }
    }

    /** Band ramp below this: pure {@code from}. Above {@link #FADE_WINDOW_END}: pure {@code to}. */
    public static final float FADE_WINDOW_START = 0.3f;
    public static final float FADE_WINDOW_END = 0.7f;

    /**
     * Hysteresis on the settled world, and why it is not optional.
     *
     * <p>The decision's only input is {@code camera.getPosition().x}, and on a train that camera
     * rides a Sable sub-level whose position moves between tick space and render space from one
     * frame to the next. A bare {@code w >= 0.5} threshold therefore flips the reported world back
     * and forth across that jitter, and every flip swaps Iris' <em>entire</em> pipeline. Measured on
     * Sildur's Enhanced Default: carriage lighting strobed from the band's leading edge onward, and
     * the train kept casting an overworld sun shadow inside the Nether band because a good share of
     * frames really were still the overworld pipeline.</p>
     *
     * <p>Entering takes {@code 0.6}, leaving takes {@code 0.4}, so the jitter band between them
     * changes nothing. {@link #MIN_DWELL_FRAMES} then caps how often a change can happen at all, so
     * no input sequence whatsoever can produce a per-frame swap.</p>
     */
    private static final float ENTER_AT = 0.6f;
    private static final float LEAVE_AT = 0.4f;
    private static final int MIN_DWELL_FRAMES = 20;

    /** Frames of reported-world history the panel's swap counter looks back over. */
    private static final int SWAP_WINDOW_FRAMES = 60;
    /** Above this many swaps in a window something is oscillating; say so once. */
    private static final int SWAP_ALARM = 2;

    private static final Blend NONE = new Blend(World.OVERWORLD, World.OVERWORLD, 0.0f);

    /** The world Iris should currently be told about, or {@code null} for the real one. Render thread. */
    private static volatile World reporting = null;

    /** The settled world the hysteresis is currently holding, and how long it has held it. */
    private static World held = World.OVERWORLD;
    private static int framesHeld = MIN_DWELL_FRAMES;

    // Reported-world changes over a rolling window, so an oscillation is visible on the panel and
    // in a sweep log rather than only to someone watching the screen at the time.
    private static int swapsThisWindow = 0;
    private static int framesThisWindow = 0;
    private static volatile int swapsLastWindow = 0;
    private static boolean swapAlarmLogged = false;

    /** The frame's decision, kept for the diagnostics panel. */
    private static volatile Blend lastBlend = NONE;
    private static volatile int lastRenders = 1;

    private static volatile boolean irisResolved = false;
    private static Object idNether;
    private static Object idEnd;
    private static Method getPipelineManager;
    private static Method preparePipeline;
    private static Method getCurrentDimension;

    private ShaderWorld() {}

    // --- Decision -----------------------------------------------------------------------------------

    /**
     * Decide this frame's blend from the camera position. Pure read of the band ramps and the
     * room-sky ease; does not advance anything.
     */
    public static Blend decide(double camX) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) return NONE;

        // The band underneath: at most one of the two is above zero at any X.
        World bandWorld = World.OVERWORLD;
        float bandRamp = 0.0f;
        double nether = ClientNetherBand.netherIntensityAt(camX);
        double end = ClientVoidBand.endSkyIntensityAt(camX);
        if (nether > 0.0) {
            bandWorld = World.NETHER;
            bandRamp = (float) nether;
        } else if (end > 0.0) {
            bandWorld = World.END;
            bandRamp = (float) end;
        }
        float bandW = bandWorld == World.OVERWORLD ? 0.0f : window(bandRamp);

        // A dimensional carriage on top: its ease runs from whatever the band had settled on.
        World roomWorld = roomWorld(ClientPortalRoomSky.sky());
        float roomW = roomWorld == null ? 0.0f : clamp(ClientPortalRoomSky.applied());

        // One stabilise per frame, over whichever of the two is actually asking. Doing it once, on
        // the final answer, is what guarantees every Iris query within a frame agrees.
        if (roomWorld == null || roomW <= 0.0f) {
            return stabilise(World.OVERWORLD, bandWorld, bandW);
        }
        World base = held == World.OVERWORLD && bandW >= ENTER_AT ? bandWorld : held;
        if (roomWorld == base) return stabilise(base, base, 1.0f);
        return stabilise(base, roomWorld, roomW);
    }

    /**
     * Apply the hysteresis and dwell, and return the blend carrying the world a single render
     * should actually report. Called exactly once per frame, from {@link #decide}.
     */
    private static Blend stabilise(World from, World to, float w) {
        if (framesHeld < Integer.MAX_VALUE) framesHeld++;

        // Between the two thresholds nothing changes, which is the whole point.
        World wanted = held;
        if (w >= ENTER_AT) {
            wanted = to;
        } else if (w <= LEAVE_AT) {
            wanted = from;
        }

        boolean swapped = false;
        if (wanted != held && framesHeld >= MIN_DWELL_FRAMES) {
            held = wanted;
            framesHeld = 0;
            swapped = true;
        }

        countSwap(swapped);
        return new Blend(from, to, w, held);
    }

    /** Roll the swap window, and shout once if the reported world is oscillating. */
    private static void countSwap(boolean swapped) {
        if (swapped) swapsThisWindow++;
        if (++framesThisWindow < SWAP_WINDOW_FRAMES) return;
        swapsLastWindow = swapsThisWindow;
        if (swapsThisWindow > SWAP_ALARM && !swapAlarmLogged) {
            swapAlarmLogged = true;
            LOGGER.warn("[DungeonTrain] Shader world oscillating: {} swaps in {} frames. The pipeline "
                + "is being rebuilt repeatedly and lighting will strobe.", swapsThisWindow, SWAP_WINDOW_FRAMES);
        }
        swapsThisWindow = 0;
        framesThisWindow = 0;
    }

    /** Swaps counted over the last completed window, for the diagnostics panel. A crossing reads 1. */
    public static int swapsLastWindow() {
        return swapsLastWindow;
    }

    /** Drop the hysteresis state. Wired to logging out, so one world's crossing never colours the next. */
    public static void reset() {
        held = World.OVERWORLD;
        framesHeld = MIN_DWELL_FRAMES;
        swapsThisWindow = 0;
        framesThisWindow = 0;
        swapsLastWindow = 0;
        swapAlarmLogged = false;
        reporting = null;
    }

    private static World roomWorld(PortalRoomSky sky) {
        if (sky == null) return null;
        return switch (sky) {
            case NETHER -> World.NETHER;
            case END -> World.END;
            default -> null;
        };
    }

    /** Remap a band ramp so the cross-fade occupies only the window's share of it. */
    static float window(float ramp) {
        if (ramp <= FADE_WINDOW_START) return 0.0f;
        if (ramp >= FADE_WINDOW_END) return 1.0f;
        return (ramp - FADE_WINDOW_START) / (FADE_WINDOW_END - FADE_WINDOW_START);
    }

    private static float clamp(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    // --- What Iris is told ------------------------------------------------------------------------

    /** Set the world every Iris query should answer with until changed. {@code null} = the real one. */
    public static void setReporting(World world) {
        reporting = world == World.OVERWORLD ? null : world;
    }

    /** The world currently being reported to Iris, or {@code null} when Iris sees the real one. */
    public static World reporting() {
        return reporting;
    }

    /**
     * The Iris {@code NamespacedId} to return from {@code Iris.getCurrentDimension()}, or
     * {@code null} to leave Iris' own answer alone. Called from the mixin on every Iris query.
     */
    public static Object irisOverride() {
        if (!ShaderBisect.spoofEnabled()) return null;
        World w = reporting;
        if (w == null) return null;
        resolveIris();
        return w == World.NETHER ? idNether : w == World.END ? idEnd : null;
    }

    /**
     * The sky angle the dimension being spoofed would actually report, or {@code null} to leave
     * Iris' own answer alone.
     *
     * <p>The real Nether and End are <em>fixed-time</em> dimensions: their {@code DimensionType}
     * carries a {@code fixedTime}, so {@code timeOfDay} ignores the world clock and the sun never
     * moves. A band is the overworld, which has no such fix, so telling a pack "this is the Nether"
     * while handing it a travelling sun left the train lit and shadowed by an overworld sun inside
     * the Nether — the one part of the illusion that stayed obviously wrong.</p>
     *
     * <p>Read from the registry rather than hard-coded, so it is whatever the dimension actually
     * declares, including if a datapack changes it.</p>
     */
    public static Float spoofedSkyAngle() {
        World w = reporting;
        if (w == null || w == World.OVERWORLD) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        try {
            ResourceKey<DimensionType> key = w == World.NETHER
                ? BuiltinDimensionTypes.NETHER : BuiltinDimensionTypes.END;
            DimensionType type = mc.level.registryAccess()
                .registryOrThrow(Registries.DIMENSION_TYPE).get(key);
            return type == null ? null : type.timeOfDay(mc.level.getDayTime());
        } catch (Throwable t) {
            return null;
        }
    }

    // --- Upside-down: a sun that never rises and never sets --------------------------------------

    /**
     * The one tick the upside-down band is pinned to: shortly after sunrise, sun a little above the
     * horizon. Everything a pack reads about the time — the sky angle, {@code worldTime}, the sky
     * colour, the shadow light — is derived from this single number so none of them can disagree.
     *
     * <p>A first cut pinned the <em>sky angle</em> to {@code 0.02}, which on vanilla's curve is just
     * past noon, not dawn: sunrise is {@code 0.785} (tick 0), noon is {@code 0.0}. Derive, do not
     * guess.</p>
     */
    public static final int DAWN_TICK = 1000;
    /** Elevation of the shadow light above the horizon, in degrees; zero degenerates a shadow map. */
    private static final float DAWN_ELEVATION_DEG = 8.0f;
    private static final float DAWN_SKY_ANGLE = skyAngleOf(DAWN_TICK);

    /** Vanilla's {@code DimensionType.timeOfDay} for a dimension with no fixed time. */
    private static float skyAngleOf(long tick) {
        double d0 = net.minecraft.util.Mth.frac((double) tick / 24000.0 - 0.25);
        double d1 = 0.5 - Math.cos(d0 * Math.PI) / 2.0;
        return (float) ((d0 * 2.0 + d1) / 3.0);
    }

    /** Ease {@code from} toward {@code to} by {@code t} along the shorter way round a unit ring. */
    private static float easeOnRing(float from, float to, float t) {
        float delta = to - from;
        if (delta > 0.5f) delta -= 1.0f;
        if (delta < -0.5f) delta += 1.0f;
        float out = from + delta * t;
        return out - (float) Math.floor(out);
    }

    /** How far into the upside-down band the camera is, {@code 0}..{@code 1}, or {@code 0}. */
    public static float upsideDownIntensity() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gameRenderer == null) return 0.0f;
        if (!mc.level.dimension().equals(Level.OVERWORLD)) return 0.0f;
        var camera = mc.gameRenderer.getMainCamera();
        if (camera == null) return 0.0f;
        return clamp((float) games.brennan.dungeontrain.client.ClientUpsideDownBand
            .upsideDownIntensityAt(camera.getPosition().x));
    }

    /**
     * The sky angle a pack should see in the upside-down band: the real one, eased toward dawn by
     * the band. The band's own sun is drawn orbiting the horizon, so the sky it sits in should read
     * as a permanent sunrise rather than cycling through noon and midnight above it.
     *
     * <p>Eased toward whichever of {@code 0} and {@code 1} is nearer, since the angle wraps: easing
     * {@code 0.9} toward {@code 0.0} directly would drag the sun back through noon on the way.</p>
     */
    public static Float upsideDownSkyAngle(float real) {
        float t = upsideDownIntensity();
        if (t <= 0.0f) return null;
        return easeOnRing(real, DAWN_SKY_ANGLE, t);
    }

    /**
     * The {@code worldTime} a pack should see in the band: the real tick eased toward
     * {@link #DAWN_TICK} the shorter way round the day. Packs read this directly for their brightness
     * and day/night factors, independently of the sky angle, so pinning the angle alone still left
     * them in the dark at midnight.
     */
    public static Integer upsideDownWorldTime(int real) {
        float t = upsideDownIntensity();
        if (t <= 0.0f) return null;
        float eased = easeOnRing(real / 24000.0f, DAWN_TICK / 24000.0f, t);
        return Math.round(eased * 24000.0f) % 24000;
    }

    /**
     * Where the band's sun is, as a world-space direction, matching the billboard
     * {@code UpsideDownSkyRenderer} draws: rotated about +Y by {@code timeOfDay * 360}, which takes
     * the body's {@code (0, 0, -1)} to {@code (-sin, 0, -cos)}, lifted by {@link #DAWN_ELEVATION_DEG}.
     * {@code null} outside the band.
     */
    public static float[] upsideDownSunDirection() {
        float t = upsideDownIntensity();
        if (t <= 0.0f) return null;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double az = Math.toRadians(mc.level.getTimeOfDay(partial) * 360.0);
        double el = Math.toRadians(DAWN_ELEVATION_DEG);
        double cx = Math.cos(el);
        return new float[] { (float) (-Math.sin(az) * cx), (float) Math.sin(el), (float) (-Math.cos(az) * cx), t };
    }

    /** Record what the frame actually did, for the panel. */
    static void recordFrame(Blend blend, int renders) {
        lastBlend = blend;
        lastRenders = renders;
    }

    /** One line for the F3+5 panel. */
    public static String describe() {
        Blend b = lastBlend;
        if (!ShaderCompat.active()) return "";
        if (!b.fading()) {
            World w = b.settled();
            return w == World.OVERWORLD ? "" : w.name().toLowerCase(Locale.ROOT) + " (" + w.folder() + ")";
        }
        return String.format(Locale.ROOT, "%s -> %s w=%.2f (%d renders)",
            b.from().name().toLowerCase(Locale.ROOT), b.to().name().toLowerCase(Locale.ROOT), b.w(), lastRenders);
    }

    /** {@code "1"} across a healthy crossing; anything above a couple means the pipeline is thrashing. */
    public static String describeSwaps() {
        return swapsLastWindow + "/" + SWAP_WINDOW_FRAMES + "f";
    }

    // --- Pre-warm -----------------------------------------------------------------------------------

    /**
     * Ask Iris to build the Nether and End pipelines now, so the first band entry does not pay the
     * shader compile mid-fade. Render thread only; a no-op without Iris or without a pack. Iris
     * caches one pipeline per id and returns the cached one thereafter, and the per-frame lookup
     * restores the real dimension's pipeline on the next render.
     */
    public static void prewarm() {
        if (!ShaderCompat.active()) return;
        resolveIris();
        if (getPipelineManager == null || preparePipeline == null || getCurrentDimension == null) return;
        World was = reporting;
        try {
            reporting = null;
            Object real = getCurrentDimension.invoke(null);
            Object manager = getPipelineManager.invoke(null);
            long t0 = System.nanoTime();
            preparePipeline.invoke(manager, idNether);
            preparePipeline.invoke(manager, idEnd);
            if (real != null) preparePipeline.invoke(manager, real);
            LOGGER.info("[DungeonTrain] Pre-warmed the shader pack's Nether and End pipelines in {} ms",
                (System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Shader pipeline pre-warm failed: {}", t.toString());
        } finally {
            reporting = was;
        }
    }

    private static void resolveIris() {
        if (irisResolved) return;
        synchronized (ShaderWorld.class) {
            if (irisResolved) return;
            try {
                Class<?> dimensionId = Class.forName("net.irisshaders.iris.shaderpack.DimensionId");
                idNether = dimensionId.getField("NETHER").get(null);
                idEnd = dimensionId.getField("END").get(null);
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                getPipelineManager = iris.getMethod("getPipelineManager");
                getCurrentDimension = iris.getMethod("getCurrentDimension");
                Class<?> namespacedId = Class.forName("net.irisshaders.iris.shaderpack.materialmap.NamespacedId");
                Class<?> pipelineManager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
                preparePipeline = pipelineManager.getMethod("preparePipeline", namespacedId);
            } catch (ClassNotFoundException absent) {
                // No Iris: the mixin never applied either, so nothing will ask.
            } catch (Throwable t) {
                LOGGER.warn("[DungeonTrain] Iris dimension ids unavailable; shader worlds stay vanilla: {}", t.toString());
            } finally {
                irisResolved = true;
            }
        }
    }
}
