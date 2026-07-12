package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.CinematicDonePacket;
import games.brennan.dungeontrain.net.CinematicIntroPacket;
import games.brennan.dungeontrain.net.platform.DtNetSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Client-side state machine + camera maths for the spawn intro cinematic.
 *
 * <p>{@link CameraCinematicMixin} calls {@link #computePose(float)} every
 * render frame while {@link #isActive()} and overrides the render camera with
 * the result. {@link CinematicInputHandler} drives the tick clock, skip, and
 * input freeze. The player's body never moves here — only the render camera.</p>
 *
 * <p>Motion: from the sent ground-spawn pose the camera rises by
 * {@code riseHeight} and eases back by {@code pullBack} (along the fixed
 * frame-0 direction away from the player), while continuously aiming at the
 * <em>live</em> local player (offset up by {@code lookYOffset}). Because the
 * train carries the player forward, the aim pans to follow — the character and
 * train stay framed as the shot widens.</p>
 *
 * <p>All methods run on the client main thread (packet {@code enqueueWork},
 * client tick, render) — no cross-thread access.</p>
 */
public final class CinematicCameraController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A no-op input swapped into {@link LocalPlayer#input} while active. The
     * base {@link Input#tick} is empty, so all movement impulses stay zero
     * regardless of held keys (the real {@code KeyboardInput} is restored on
     * end). Field-swap rather than per-tick zeroing because
     * {@code KeyboardInput.tick()} runs inside {@code aiStep} after
     * {@code ClientTickEvent.Pre} and would otherwise re-arm movement.
     */
    private static final Input FROZEN_INPUT = new Input();

    /** Fraction of the run spent blending rotation from the sent start pose into the look-at (no frame-0 pop). */
    private static final double START_BLEND_FRACTION = 0.15;
    /** Ticks spent easing the camera into the player's eye on end (no hard cut). */
    private static final int RELEASE_BLEND_TICKS = 8;

    private static volatile boolean active = false;

    // Packet-supplied parameters.
    private static Vec3 startPos = Vec3.ZERO;
    private static float startYaw;
    private static float startPitch;
    private static double riseHeight;
    private static double pullBack;
    private static double lookYOffset;
    private static int durationTicks = 1;

    // Fixed (frame-0) horizontal direction the camera pulls back along.
    private static double backDirX;
    private static double backDirZ;

    // Clock / phase.
    private static int elapsedTicks;
    private static boolean releasing;
    private static int releaseTicks;

    // Captured at end() so the release phase blends from the last cinematic pose.
    private static Vec3 releaseFromPos = Vec3.ZERO;
    private static float releaseFromYaw;
    private static float releaseFromPitch;

    // Player view + input saved at start, restored on finish.
    private static float savedYaw;
    private static float savedPitch;
    private static Input savedInput;
    private static boolean savedHideGui;

    private CinematicCameraController() {}

    /** Resolved camera pose for one render frame. */
    public record Pose(Vec3 pos, float yaw, float pitch) {}

    public static boolean isActive() {
        return active;
    }

    /** Begin the cinematic (called from {@link CinematicIntroPacket#handle}). */
    public static void start(CinematicIntroPacket p) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        startPos = new Vec3(p.camX(), p.camY(), p.camZ());
        startYaw = p.startYaw();
        startPitch = p.startPitch();
        riseHeight = p.riseHeight();
        pullBack = p.pullBack();
        lookYOffset = p.lookYOffset();
        durationTicks = Math.max(1, p.durationTicks());

        // Pull-back direction = away from the player at frame 0 (fixed for the run).
        double bdx = startPos.x - player.getX();
        double bdz = startPos.z - player.getZ();
        double blen = Math.sqrt(bdx * bdx + bdz * bdz);
        backDirX = blen > 1.0e-6 ? bdx / blen : 0.0;
        backDirZ = blen > 1.0e-6 ? bdz / blen : 0.0;

        elapsedTicks = 0;
        releasing = false;
        releaseTicks = 0;

        savedYaw = player.getYRot();
        savedPitch = player.getXRot();
        savedInput = player.input;
        player.input = FROZEN_INPUT;
        clearImpulses(FROZEN_INPUT);

        // Hide the whole HUD for a clean spectator shot (restored on end).
        savedHideGui = mc.options.hideGui;
        mc.options.hideGui = true;

        CinematicSkipHudOverlay.reset();
        active = true;
        LOGGER.info("[DungeonTrain] Intro cinematic start: cam={} yaw={} pitch={} rise={} pull={} dur={}t",
            startPos, startYaw, startPitch, riseHeight, pullBack, durationTicks);
    }

    /** Advance the clock once per client tick. Called from {@link CinematicInputHandler}. */
    public static void clientTick() {
        if (!active) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            forceStop();
            return;
        }
        // A screen owns input (e.g. the focus-loss pause menu). Suspend the cinematic —
        // freeze the clock and let the screen work normally; resume when it closes. The
        // frozen input is left in place, so movement stays locked across the pause.
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        // Keep movement frozen (defensive against re-assignment).
        if (player.input != FROZEN_INPUT) {
            player.input = FROZEN_INPUT;
        }
        clearImpulses(FROZEN_INPUT);

        if (releasing) {
            releaseTicks++;
            if (releaseTicks >= RELEASE_BLEND_TICKS) {
                finishRelease(player);
            }
            return;
        }

        elapsedTicks++;
        if (elapsedTicks >= durationTicks) {
            end(true);
        }
    }

    /** Player pressed Space. */
    public static void skip() {
        end(true);
    }

    /**
     * Begin ending the cinematic: send the server the done-signal and enter a
     * short release blend toward the player eye. Idempotent once releasing.
     */
    public static void end(boolean sendDone) {
        if (!active || releasing) return;
        if (sendDone) {
            DtNetSender.get().sendToServer(new CinematicDonePacket());
        }
        if (RELEASE_BLEND_TICKS > 0) {
            Pose now = computePose(1.0f);
            releaseFromPos = now.pos();
            releaseFromYaw = now.yaw();
            releaseFromPitch = now.pitch();
            releasing = true;
            releaseTicks = 0;
            CinematicSkipHudOverlay.reset();
        } else {
            finishRelease(Minecraft.getInstance().player);
        }
    }

    /** Hard reset with no blend (e.g. the player is leaving the world). */
    public static void forceStop() {
        active = false;
        releasing = false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && savedInput != null && player.input == FROZEN_INPUT) {
            player.input = savedInput;
        }
        savedInput = null;
        Minecraft.getInstance().options.hideGui = savedHideGui;
        CinematicSkipHudOverlay.reset();
    }

    private static void finishRelease(LocalPlayer player) {
        active = false;
        releasing = false;
        if (player != null) {
            if (savedInput != null && player.input == FROZEN_INPUT) {
                player.input = savedInput;
            }
            player.setYRot(savedYaw);
            player.setXRot(savedPitch);
            player.yRotO = savedYaw;
            player.xRotO = savedPitch;
        }
        savedInput = null;
        Minecraft.getInstance().options.hideGui = savedHideGui;
        CinematicSkipHudOverlay.reset();
    }

    /**
     * Camera pose for a render frame. {@code partialTick} smooths both the
     * tick clock and the live player interpolation.
     */
    public static Pose computePose(float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new Pose(startPos, startYaw, startPitch);
        }

        // Live, interpolated local-player position; aim slightly up its body.
        double lpx = Mth.lerp(partialTick, player.xo, player.getX());
        double lpy = Mth.lerp(partialTick, player.yo, player.getY());
        double lpz = Mth.lerp(partialTick, player.zo, player.getZ());
        Vec3 lookTarget = new Vec3(lpx, lpy + lookYOffset, lpz);

        if (releasing) {
            double tr = Mth.clamp((releaseTicks + partialTick) / (double) RELEASE_BLEND_TICKS, 0.0, 1.0);
            double er = smoothstep(tr);
            Vec3 eye = new Vec3(lpx, lpy + player.getEyeHeight(), lpz);
            Vec3 pos = lerp(releaseFromPos, eye, er);
            float yaw = Mth.rotLerp((float) er, releaseFromYaw, savedYaw);
            float pitch = (float) Mth.lerp(er, releaseFromPitch, savedPitch);
            return new Pose(pos, yaw, pitch);
        }

        double t = Mth.clamp((elapsedTicks + partialTick) / (double) durationTicks, 0.0, 1.0);
        double e = smoothstep(t);

        Vec3 pos = new Vec3(
            startPos.x + backDirX * pullBack * e,
            startPos.y + riseHeight * e,
            startPos.z + backDirZ * pullBack * e);

        float[] yp = lookAt(pos, lookTarget);
        float yaw = yp[0];
        float pitch = yp[1];

        // Blend the sent ground-spawn rotation into the look-at over the start.
        if (t < START_BLEND_FRACTION) {
            double be = smoothstep(t / START_BLEND_FRACTION);
            yaw = Mth.rotLerp((float) be, startYaw, yaw);
            pitch = (float) Mth.lerp(be, startPitch, pitch);
        }
        return new Pose(pos, yaw, pitch);
    }

    // ── maths helpers ────────────────────────────────────────────────────

    private static double smoothstep(double t) {
        double c = Mth.clamp(t, 0.0, 1.0);
        return c * c * (3.0 - 2.0 * c);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(Mth.lerp(t, a.x, b.x), Mth.lerp(t, a.y, b.y), Mth.lerp(t, a.z, b.z));
    }

    /** Yaw/pitch (MC convention) to look from {@code pos} toward {@code target}. */
    private static float[] lookAt(Vec3 pos, Vec3 target) {
        double dx = target.x - pos.x;
        double dy = target.y - pos.y;
        double dz = target.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new float[] { yaw, pitch };
    }

    private static void clearImpulses(Input in) {
        in.leftImpulse = 0.0f;
        in.forwardImpulse = 0.0f;
        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }
}
