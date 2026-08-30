package games.brennan.dungeontrain.client.death;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.CinematicCameraController.Pose;
import games.brennan.dungeontrain.client.CinematicSkipHudOverlay;
import games.brennan.dungeontrain.client.snapshot.CameraClip;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * The three shots a run ends on: the body where it fell, then the train running on without it.
 *
 * <p>Dying used to cut straight to the recap, and the recap paints an opaque backdrop from its
 * first frame ({@code NarrativeDeathScreen}) — so the train, which never stops, was simply never
 * seen again. This holds the recap for ~8 seconds and gives that ending a camera:</p>
 *
 * <ol>
 *   <li><b>The fall</b> — a low three-quarter push-in on the body, drifting the way the train
 *       travels so the deck slides under the lens.</li>
 *   <li><b>Alongside</b> — a tracking shot pinned to the moving train, aimed up the line: the
 *       camera holds the carriages and the world streams past them.</li>
 *   <li><b>Left behind</b> — anchored to the body instead, craning up and back while the train
 *       pulls away toward the horizon.</li>
 * </ol>
 *
 * <p>Shots are separated by a short dip to black ({@link #blackAlpha}), which also opens the first
 * shot and closes the last — so the sequence rises out of the death and settles into the recap.
 * Space or a click cuts to the recap at any point.</p>
 *
 * <p>Kept apart from {@code CinematicCameraController} on purpose: that one is a single
 * player-relative shot with an input freeze and a release blend back into the player's eye, and
 * none of those apply to a dead player being handed to a screen. What is shared is its
 * {@link Pose} record, the skip prompt, and the lens maths in {@link CameraClip}.</p>
 *
 * <p>All state is client-main-thread only (packet {@code enqueueWork}, client tick, render).</p>
 */
public final class DeathCinematic {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Shot lengths in ticks — ~2.5s, ~2.5s, ~3s. */
    private static final int FALL_TICKS = 50;
    private static final int ALONGSIDE_TICKS = 50;
    private static final int LEFT_BEHIND_TICKS = 60;
    private static final int TOTAL_TICKS = FALL_TICKS + ALONGSIDE_TICKS + LEFT_BEHIND_TICKS;

    /** Length of the dip to black at each cut (and at the head and tail of the sequence). */
    private static final int DIP_TICKS = 6;

    /**
     * Hard stop. The clock is suspended while a screen is open (the pause menu), so this is
     * wall-of-last-resort protection against a wedged sequence, not a second timer: a dead player
     * must never be stranded in a camera with no recap and no button.
     */
    private static final int TIMEOUT_TICKS = TOTAL_TICKS * 4;

    /** The train runs +X unless the carriages say otherwise (see {@code DungeonTrainConfig.speed}). */
    private static final Vec3 DEFAULT_AXIS = new Vec3(1.0, 0.0, 0.0);

    /**
     * How long the opening dip gives the shot to find a carriage near the body before giving up
     * (see {@link #aborted}). Comfortably inside {@link #DIP_TICKS}, so an abort is never seen.
     */
    private static final int ABORT_WINDOW_TICKS = 4;

    /** A centroid jump larger than this is the carriage set changing, not the train moving. */
    private static final double MAX_CENTROID_STEP = 2.0;

    private static volatile boolean active = false;

    /** What happens when the sequence ends: open the recap, or reboard. Run exactly once. */
    private static Runnable onEnd;

    private static int elapsedTicks;
    private static int ticksSinceStart;

    /** Where the body fell, captured on the first render frame (world space — see {@link #computePose}). */
    private static Vec3 anchor;

    /** Unit horizontal travel direction, measured from the carriages' centroid between ticks. */
    private static Vec3 axis = DEFAULT_AXIS;
    private static Vec3 prevCenter;

    /** Set by the first render frame when the body turned out to be nowhere near the train. */
    private static boolean aborted;

    private static boolean savedHideGui;
    private static Pose lastPose;

    private DeathCinematic() {}

    /**
     * Play the sequence, then run {@code after}. Returns {@code false} when there is no shot to be
     * had — the toggle is off, the world is gone, or no carriage is in range (died off the line, or
     * the recap is being opened from somewhere that isn't a run) — in which case nothing is started
     * and the caller must do {@code after} itself, immediately, exactly as before this existed.
     */
    public static boolean playThen(Runnable after) {
        if (active) return false;
        if (!ClientDisplayConfig.isDeathCinematicEnabled()) return false;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return false;
        if (!TrainShotFraming.hasCarriages(level)) return false;

        onEnd = after;
        aborted = false;
        elapsedTicks = 0;
        ticksSinceStart = 0;
        anchor = null;
        axis = DEFAULT_AXIS;
        prevCenter = null;
        lastPose = null;
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;
        CinematicSkipHudOverlay.reset();
        active = true;
        LOGGER.info("[DungeonTrain] Death cinematic start: {} ticks over three shots", TOTAL_TICKS);
        return true;
    }

    public static boolean isActive() {
        return active;
    }

    /** Advance the clock once per client tick. Called from {@code CinematicInputHandler}. */
    public static void clientTick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            forceStop();
            return;
        }
        if (aborted) {
            LOGGER.info("[DungeonTrain] Death cinematic: no carriage near the body — handing straight to the recap");
            finish();
            return;
        }
        ticksSinceStart++;
        if (ticksSinceStart >= TIMEOUT_TICKS) {
            LOGGER.warn("[DungeonTrain] Death cinematic hit its timeout — handing over to the recap");
            finish();
            return;
        }
        // A screen owns the view (the pause menu, or focus loss): suspend rather than play the
        // shot behind it, and resume when it closes — the same contract the intro cinematic keeps.
        if (mc.screen != null) return;

        sampleAxis(mc.level);

        elapsedTicks++;
        if (elapsedTicks >= TOTAL_TICKS) {
            finish();
        }
    }

    /** Player pressed Space or clicked: cut to the recap. */
    public static void skip() {
        if (!active) return;
        LOGGER.info("[DungeonTrain] Death cinematic skipped at tick {}", elapsedTicks);
        finish();
    }

    /** Hard reset with no hand-over (the player is leaving the world). */
    public static void forceStop() {
        if (!active) return;
        active = false;
        onEnd = null;
        anchor = null;
        prevCenter = null;
        lastPose = null;
        Minecraft.getInstance().options.hideGui = savedHideGui;
        CinematicSkipHudOverlay.reset();
    }

    /**
     * End the sequence and hand over. {@link #active} is cleared <em>before</em> the callback runs:
     * it opens a screen, and the guards that keep other screens out during the shot key off that
     * flag.
     */
    private static void finish() {
        Runnable after = onEnd;
        active = false;
        onEnd = null;
        anchor = null;
        prevCenter = null;
        lastPose = null;
        Minecraft.getInstance().options.hideGui = savedHideGui;
        CinematicSkipHudOverlay.reset();
        if (after != null) after.run();
    }

    /**
     * Measure the travel direction from how far the carriages' centroid moved this tick. A
     * carriage entering or leaving the gather radius moves the centroid without the train having
     * moved, so a step larger than {@link #MAX_CENTROID_STEP} is discarded rather than believed.
     */
    private static void sampleAxis(ClientLevel level) {
        if (anchor == null) return; // no render frame yet — nothing to gather around
        TrainShotFraming.TrainView view = TrainShotFraming.resolve(level, anchor, axis);
        if (view == null) return;
        if (prevCenter != null) {
            Vec3 step = view.center().subtract(prevCenter);
            Vec3 flat = new Vec3(step.x, 0.0, step.z);
            double len = flat.length();
            if (len > 0.01 && len < MAX_CENTROID_STEP) {
                axis = flat.scale(1.0 / len);
            }
        }
        prevCenter = view.center();
    }

    // ── the shots ────────────────────────────────────────────────────────

    /**
     * Camera pose for one render frame.
     *
     * <p>Render time is the only place the body's position may be read: at tick time a player
     * aboard a Sable ship reports far sub-level coords (the caveat {@code SnapshotCamera} carries).
     * The anchor is captured on the first frame and then held, so shots 1 and 3 stay put in the
     * world while the train leaves — which is the whole point of the last one.</p>
     */
    public static Pose computePose(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return lastPose != null ? lastPose : new Pose(Vec3.ZERO, 0.0f, 0.0f);
        }
        if (anchor == null) {
            anchor = new Vec3(
                    Mth.lerp(partialTick, player.xo, player.getX()),
                    Mth.lerp(partialTick, player.yo, player.getY()),
                    Mth.lerp(partialTick, player.zo, player.getZ()));
        }

        Vec3 perp = new Vec3(-axis.z, 0.0, axis.x);
        TrainShotFraming.TrainView view = TrainShotFraming.resolve(level, anchor, axis);
        if (view == null && elapsedTicks <= ABORT_WINDOW_TICKS) {
            // The body's real world position has no carriage in range: this death wasn't on the
            // line. Still inside the opening dip, so the frame is black and the next tick's
            // hand-over to the recap is invisible. Judged over a few ticks rather than exactly one
            // because a client tick can land before the first render frame does.
            aborted = true;
        }
        Vec3 trainCenter = view != null ? view.center() : anchor;
        Vec3 trainFront = view != null ? view.front() : anchor;

        double t = elapsedTicks + partialTick;
        Vec3 want;
        Vec3 look;
        if (t < FALL_TICKS) {
            double e = smoothstep(t / FALL_TICKS);
            // Push in on the body from a low three-quarter angle, drifting downtrain as it goes.
            want = anchor
                    .add(perp.scale(3.6 - 1.4 * e))
                    .add(axis.scale(-2.0 + 3.0 * e))
                    .add(0.0, 2.2 - 0.9 * e, 0.0);
            look = anchor.add(0.0, 0.9, 0.0);
        } else if (t < FALL_TICKS + ALONGSIDE_TICKS) {
            double e = smoothstep((t - FALL_TICKS) / ALONGSIDE_TICKS);
            // Pinned to the train's own centre, so the camera runs with it and the world streams past.
            want = trainCenter
                    .add(perp.scale(7.5))
                    .add(axis.scale(-9.0 + 6.0 * e))
                    .add(0.0, 2.4 + 1.2 * e, 0.0);
            look = trainCenter.add(axis.scale(4.0)).add(0.0, 1.6, 0.0);
        } else {
            double e = smoothstep((t - FALL_TICKS - ALONGSIDE_TICKS) / LEFT_BEHIND_TICKS);
            // Back on the body's fixed spot, craning up and away while the train recedes.
            want = anchor
                    .add(perp.scale(1.5 + 4.0 * e))
                    .add(axis.scale(-1.0 - 7.0 * e))
                    .add(0.0, 3.0 + 11.0 * e, 0.0);
            look = trainFront.add(0.0, 1.0, 0.0);
        }

        Vec3 pos = CameraClip.towardOpenAir(level, player, look, want);
        float[] yp = CameraClip.lookAt(pos, look);
        lastPose = new Pose(pos, yp[0], yp[1]);
        return lastPose;
    }

    /**
     * Opacity (0..1) of the black laid over the frame: full at every cut, so the shots are
     * separated by a dip rather than a hard jump, and the sequence opens out of and closes back
     * into black. Drawn by {@link DeathCinematicOverlay}.
     */
    public static float blackAlpha(float partialTick) {
        if (!active) return 0.0f;
        double t = elapsedTicks + partialTick;
        double intoShot;
        double shotLength;
        if (t < FALL_TICKS) {
            intoShot = t;
            shotLength = FALL_TICKS;
        } else if (t < FALL_TICKS + ALONGSIDE_TICKS) {
            intoShot = t - FALL_TICKS;
            shotLength = ALONGSIDE_TICKS;
        } else {
            intoShot = t - FALL_TICKS - ALONGSIDE_TICKS;
            shotLength = LEFT_BEHIND_TICKS;
        }
        double in = Mth.clamp(intoShot / DIP_TICKS, 0.0, 1.0);
        double out = Mth.clamp((shotLength - intoShot) / DIP_TICKS, 0.0, 1.0);
        return (float) (1.0 - Math.min(in, out));
    }

    private static double smoothstep(double t) {
        double c = Mth.clamp(t, 0.0, 1.0);
        return c * c * (3.0 - 2.0 * c);
    }
}
