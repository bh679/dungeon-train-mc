package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.client.GraphicsCapabilities;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * Captures a third-person ride photo <em>without hijacking the on-screen view</em>
 * by rendering the world <b>twice</b> within the frame's normal
 * {@code renderLevel} pass: the snapshot pose is drawn FIRST (into the main
 * target, grabbed, then discarded), and the real player view is drawn LAST by
 * the original pass — so the real view is what gets presented and the snapshot
 * pose never reaches the screen (no flicker).
 *
 * <p>Why not an off-screen {@code RenderTarget}? Veil (and Sodium, bundled by
 * Sable) manage/cache the main framebuffer, so a runtime swap of
 * {@code Minecraft.mainRenderTarget} does not redirect the world render — the
 * off-screen target stays empty (black). Two ordinary, in-context
 * {@code renderLevel} calls, by contrast, are handled by those pipelines exactly
 * like normal frames; nothing is redirected.</p>
 *
 * <p>The pose is computed <b>at render time</b> — a player riding a Sable ship
 * reports far sub-level coordinates during the tick but renders at the real
 * world position, so a tick-time pose would place the camera millions of blocks
 * away (sky only). The director just chooses the tag.</p>
 *
 * <p>Driven by {@code GameRendererSnapshotMixin} at {@code renderLevel} HEAD:
 * {@link #beginNestedCapture} runs the extra snapshot pass, grabs it, and
 * restores the camera before the original pass proceeds. {@code CameraCinematicMixin}
 * applies the pose while {@link #isCapturing()} is set (during the nested pass only).</p>
 */
public final class RideSnapshotCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Long-edge resolution ordinary shots are down-scaled to. */
    private static final int BASE_EDGE = 1080;
    /** Long-edge for a hi-res shot on a machine with FPS headroom (clamped to the real framebuffer — never upscaled). */
    private static final int HIRES_EDGE_HIGH = 2160;
    /** Long-edge for a hi-res shot when the machine is under FPS pressure at capture time. */
    private static final int HIRES_EDGE_MID = 1440;
    /** At/above this FPS the hi-res branch picks {@link #HIRES_EDGE_HIGH}; below it, {@link #HIRES_EDGE_MID}. */
    private static final int HIRES_FPS_THRESHOLD = 55;
    /** Tag used to frame a targeted echo capture — SOCIAL gives a front/side portrait angle. */
    private static final SnapshotTag SUBJECT_TAG = SnapshotTag.SOCIAL;
    /** Render frames a targeted echo capture keeps retrying a clean angle before giving up (~1.3 s @ 60 fps). */
    private static final int SUBJECT_CAPTURE_RETRY_FRAMES = 80;

    private static volatile boolean capturing = false;
    private static CinematicCameraController.Pose capturePose;
    private static SnapshotTag captureTag;

    private static volatile SnapshotTag pendingTag;

    // ── Targeted (echo) capture: frame an arbitrary subject entity and hand the PNG to a callback ──
    private static volatile int pendingSubjectId = -1;
    private static volatile Consumer<byte[]> pendingSubjectCallback;
    private static int subjectRetries;                 // render frames left to find a clean angle

    private RideSnapshotCapture() {}

    // ── Mixin hooks (camera override) ────────────────────────────────────
    public static boolean isCapturing() { return capturing; }
    public static CinematicCameraController.Pose capturePose() { return capturePose; }

    // ── Director API ─────────────────────────────────────────────────────
    /** Queue a shot of this tag; the pose is built at render time. */
    public static void request(SnapshotTag tag) {
        if (tag != null) pendingTag = tag;
    }

    /**
     * Queue a one-shot framed capture of the entity {@code subjectEntityId} (a remote echo) and hand
     * the resulting PNG bytes to {@code onPng} instead of committing to the gallery. The pose is built
     * around that entity at render time (correct world coords on a Sable ship); if no clean, lit angle
     * is found within a bounded number of frames the request is dropped (the caller falls back to a
     * text-only story). Set from the {@code CaptureEchoPacket} handler.
     */
    public static void requestEchoCapture(int subjectEntityId, Consumer<byte[]> onPng) {
        if (onPng == null) return;
        pendingSubjectId = subjectEntityId;
        pendingSubjectCallback = onPng;
        subjectRetries = SUBJECT_CAPTURE_RETRY_FRAMES;
    }

    /** A normal gallery shot, or a targeted echo capture, is queued or in flight. */
    public static boolean hasPending() { return pendingTag != null || pendingSubjectCallback != null; }

    /**
     * {@code renderLevel} HEAD. If a shot is pending, render one extra full pass from the snapshot
     * pose into the main target, grab it, and restore the camera — all before the original pass runs,
     * so the player's real view (drawn by that original pass) overwrites this one and is what reaches
     * the screen. Re-entrant: the nested {@code renderLevel} call is short-circuited by the
     * {@code capturing} guard so we render the extra pass exactly once.
     */
    public static void beginNestedCapture(GameRenderer gr, DeltaTracker deltaTracker) {
        if (capturing || !hasPending()) return; // guard our own nested pass / nothing to do
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        // ── Arm the pose. A targeted echo capture takes priority — a rare, explicit, once-per-encounter
        // shot — and owns this frame's slot whether or not it arms (bounded retry handles giving up), so
        // a gallery shot never sneaks in under it. ──
        Consumer<byte[]> subjectCb = null;
        if (pendingSubjectCallback != null) {
            Entity subject = level.getEntity(pendingSubjectId);
            CinematicCameraController.Pose pose = (subject != null && subject.isAlive())
                    ? SnapshotCamera.poseFor(level, SUBJECT_TAG, subject, partialTick)
                    : null;
            if (pose == null) {
                // Not ready this frame (entity gone, too dark, or boxed in) — retry next frame, then give up.
                if (--subjectRetries <= 0) {
                    pendingSubjectCallback = null;
                    pendingSubjectId = -1;
                }
                return;
            }
            captureTag = SUBJECT_TAG;
            capturePose = pose;
            subjectCb = pendingSubjectCallback;
            pendingSubjectCallback = null;
            pendingSubjectId = -1;
        } else {
            SnapshotTag tag = pendingTag;
            if (tag == null) return;
            pendingTag = null;
            // Pose in render space (correct world coords). Null = too dark or no clear/unobstructed
            // angle → skip; the director requests again shortly (its cool-down throttles retries).
            CinematicCameraController.Pose pose = SnapshotCamera.poseFor(level, tag, player, partialTick);
            if (pose == null) return;
            captureTag = tag;
            capturePose = pose;
        }

        CameraType savedCameraType = mc.options.getCameraType();
        capturing = true;
        // Third-person camera type → detached view (no hand) at the player's normal FOV.
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        gr.setRenderBlockOutline(false);
        try {
            // Extra in-context render pass from the snapshot pose. The nested renderLevel's own
            // HEAD hook short-circuits on the `capturing` guard, so this runs exactly once.
            mc.getMainRenderTarget().bindWrite(true);
            gr.renderLevel(deltaTracker);
            grab(mc, level, subjectCb);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot capture failed", e);
        } finally {
            capturing = false;
            mc.options.setCameraType(savedCameraType);
            gr.setRenderBlockOutline(true);
            // Re-bind the main target so the original renderLevel pass draws the real view cleanly.
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** Read back the just-rendered snapshot-pose frame and route it to the gallery or echo callback. */
    private static void grab(Minecraft mc, ClientLevel level, Consumer<byte[]> subjectCb) {
        NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        // Echo/portrait captures stay at the base edge (kept small for the packet); gallery shots go
        // hi-res when Distant Horizons + shaders/Fabulous make the extra pixels worthwhile.
        int edge = targetEdge(subjectCb != null);
        NativeImage shot = downscale(full, edge);
        if (shot != full) full.close();   // downscale returns src as-is when already small

        if (subjectCb != null) {
            // Targeted echo capture: JPEG-encode the grabbed frame and hand it to the callback —
            // it never enters the gallery, so we own and must close this NativeImage.
            byte[] jpeg;
            try {
                jpeg = SnapshotJpegEncoder.encode(shot);
            } finally {
                shot.close();
            }
            if (jpeg != null) {
                subjectCb.accept(jpeg);
                LOGGER.debug("[DungeonTrain] Echo snapshot captured ({} bytes)", jpeg.length);
            }
        } else {
            DynamicTexture texture = new DynamicTexture(shot);
            ResourceLocation id = mc.getTextureManager().register("dungeontrain_ride", texture);
            long tick = level.getGameTime();
            // Sample the per-photo facets (biome/band/difficulty/cart) at the moment of capture — the
            // player is alive and aboard here, so this reflects where/when the shot was actually taken.
            SnapshotMeta meta = SnapshotMeta.sample(level, mc.player);
            // Keep the DynamicTexture on the snapshot so it can be flushed to disk later (reading
            // its pixels), freeing this texture when the game has headroom and a menu is open.
            RideSnapshotGallery.add(
                    new RideSnapshot(texture, id, captureTag, meta, shot.getWidth(), shot.getHeight(), tick),
                    ClientDisplayConfig.getRideSnapshotMaxStored(),
                    ClientDisplayConfig.getRideSnapshotMaxOnDisk());
            RideSnapshotDirector.onCaptureCommitted(captureTag);
            LOGGER.debug("[DungeonTrain] Ride snapshot {} tag={} ({}x{}) gfx={} gallery={}",
                    id, captureTag, shot.getWidth(), shot.getHeight(), meta.gfx(), RideSnapshotGallery.size());
        }
    }

    /**
     * The long-edge the stored/uploaded shot is down-scaled to. Echo captures always use {@link #BASE_EDGE}.
     * A gallery shot goes hi-res only when {@link GraphicsCapabilities#wantsHiRes()} (Distant Horizons + a
     * shaderpack or Fabulous), choosing {@link #HIRES_EDGE_HIGH} vs {@link #HIRES_EDGE_MID} by live FPS so a
     * machine already under pressure isn't handed the largest frame. {@code downscale} never upscales, so on
     * a sub-2160 window the real framebuffer resolution is the true ceiling regardless.
     */
    private static int targetEdge(boolean echo) {
        if (echo || !GraphicsCapabilities.wantsHiRes()) return BASE_EDGE;
        Minecraft mc = Minecraft.getInstance();
        int fps = mc != null ? mc.getFps() : 0;
        return fps >= HIRES_FPS_THRESHOLD ? HIRES_EDGE_HIGH : HIRES_EDGE_MID;
    }

    /** Nearest-neighbour down-scale so the long edge is at most {@code maxEdge}. */
    private static NativeImage downscale(NativeImage src, int maxEdge) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int dw, dh;
        if (sw >= sh) {
            dw = Math.min(maxEdge, sw);
            dh = Math.max(1, Math.round(dw * (float) sh / sw));
        } else {
            dh = Math.min(maxEdge, sh);
            dw = Math.max(1, Math.round(dh * (float) sw / sh));
        }
        if (dw == sw && dh == sh) {
            return src; // already small enough; caller keeps the returned image
        }
        NativeImage dst = new NativeImage(dw, dh, false);
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            for (int x = 0; x < dw; x++) {
                dst.setPixelRGBA(x, y, src.getPixelRGBA(x * sw / dw, sy));
            }
        }
        return dst;
    }

    /** Drop any pending/in-flight capture (world leave). */
    public static void disposeTarget() {
        pendingTag = null;
        pendingSubjectCallback = null;
        pendingSubjectId = -1;
    }
}
