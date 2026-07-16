package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.mixin.client.MinecraftMainRenderTargetAccessor;
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
 * Captures a third-person ride photo by rendering the world an <em>extra</em>
 * time into a private off-screen {@link RenderTarget} — the player's on-screen
 * frame is never touched (no flicker). Driven from {@code GameRendererSnapshotMixin}
 * at the TAIL of {@code GameRenderer.render}, after the live frame (world + GUI)
 * is already drawn: {@link #runOffscreenCapture} builds the pose, redirects
 * {@link Minecraft#getMainRenderTarget()} to our off-screen target (via
 * {@link MinecraftMainRenderTargetAccessor}), runs one {@code renderLevel} pass
 * from the snapshot pose, reads the pixels back, then restores the real target.
 *
 * <p>The pose is computed <b>here, at render time</b>, not by the director at
 * tick time — a player riding a Sable ship reports far sub-level coordinates
 * during the tick but renders at the real world position, so a tick-time pose
 * would place the camera millions of blocks away (sky only). The director just
 * chooses the tag.</p>
 *
 * <p>The off-screen target is sized to match the main target so the projection
 * (derived from the window) is correct and {@code LevelRenderer}'s auxiliary
 * targets can {@code copyDepthFrom} it. While the pass runs,
 * {@code CameraCinematicMixin} applies the pose (keyed on {@link #isCapturing()}
 * / {@link #capturePose()}).</p>
 */
public final class RideSnapshotCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Long-edge resolution stored shots are down-scaled to. */
    private static final int MAX_EDGE = 640;
    /** Tag used to frame a targeted echo capture — SOCIAL gives a front/side portrait angle. */
    private static final SnapshotTag SUBJECT_TAG = SnapshotTag.SOCIAL;
    /** Render frames a targeted echo capture keeps retrying a clean angle before giving up (~1.3 s @ 60 fps). */
    private static final int SUBJECT_CAPTURE_RETRY_FRAMES = 80;

    private static volatile boolean capturing = false;
    private static CinematicCameraController.Pose capturePose;
    private static SnapshotTag captureTag;

    /** Reused off-screen colour+depth target for the extra render pass (render thread only). */
    private static RenderTarget offscreenTarget;

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
     * {@code GameRenderer.render} TAIL: if a shot is pending, build the render-space pose and render
     * the world one extra time from it into our off-screen target, read it back, and route the result
     * to the gallery or the echo callback. Runs entirely off the on-screen frame — the live view the
     * player already saw this frame is left intact.
     */
    public static void runOffscreenCapture(GameRenderer gr, DeltaTracker deltaTracker) {
        if (capturing || !hasPending()) return;
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

        RenderTarget realMain = mc.getMainRenderTarget();
        RenderTarget offscreen = acquireTarget(realMain);
        if (offscreen == null) return; // window not sized yet

        CameraType savedCameraType = mc.options.getCameraType();
        capturing = true;
        // Third-person camera type → detached view (no hand) at the player's normal FOV.
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        gr.setRenderBlockOutline(false);
        try {
            // Redirect the world render to our off-screen target, draw one frame from the pose, grab it.
            ((MinecraftMainRenderTargetAccessor) mc).dungeontrain$setMainRenderTarget(offscreen);
            offscreen.bindWrite(true);
            offscreen.clear(Minecraft.ON_OSX);
            gr.renderLevel(deltaTracker);

            NativeImage full = Screenshot.takeScreenshot(offscreen);
            NativeImage shot = downscale(full, MAX_EDGE);
            if (shot != full) full.close();   // downscale returns src as-is when already small

            if (subjectCb != null) {
                // Targeted echo capture: PNG-encode the grabbed frame and hand it to the callback —
                // it never enters the gallery, so we own and must close this NativeImage.
                byte[] png = null;
                try {
                    png = shot.asByteArray();
                } catch (Exception e) {
                    LOGGER.warn("[DungeonTrain] Echo snapshot PNG encode failed", e);
                } finally {
                    shot.close();
                }
                if (png != null) {
                    subjectCb.accept(png);
                    LOGGER.debug("[DungeonTrain] Echo snapshot captured ({} bytes)", png.length);
                }
            } else {
                DynamicTexture texture = new DynamicTexture(shot);
                ResourceLocation id = mc.getTextureManager().register("dungeontrain_ride", texture);
                long tick = level.getGameTime();
                // Keep the DynamicTexture on the snapshot so it can be flushed to disk later (reading
                // its pixels), freeing this texture when the game has headroom and a menu is open.
                RideSnapshotGallery.add(
                        new RideSnapshot(texture, id, captureTag, shot.getWidth(), shot.getHeight(), tick),
                        ClientDisplayConfig.getRideSnapshotMaxStored(),
                        ClientDisplayConfig.getRideSnapshotMaxOnDisk());
                RideSnapshotDirector.onCaptureCommitted(captureTag);
                LOGGER.debug("[DungeonTrain] Ride snapshot {} tag={} ({}x{}) gallery={}",
                        id, captureTag, shot.getWidth(), shot.getHeight(), RideSnapshotGallery.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot capture failed", e);
        } finally {
            // Restore the real on-screen target before render() returns / the frame is blitted.
            ((MinecraftMainRenderTargetAccessor) mc).dungeontrain$setMainRenderTarget(realMain);
            realMain.bindWrite(true);
            capturing = false;
            mc.options.setCameraType(savedCameraType);
            gr.setRenderBlockOutline(true);
        }
    }

    /** Lazily create / resize the off-screen target to match the main target (same aspect + depth copy). */
    private static RenderTarget acquireTarget(RenderTarget main) {
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) return null;
        if (offscreenTarget == null) {
            offscreenTarget = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            offscreenTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        } else if (offscreenTarget.width != w || offscreenTarget.height != h) {
            offscreenTarget.resize(w, h, Minecraft.ON_OSX);
        }
        return offscreenTarget;
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

    /** Drop any pending/in-flight capture and free the off-screen target (world leave). */
    public static void disposeTarget() {
        pendingTag = null;
        pendingSubjectCallback = null;
        pendingSubjectId = -1;
        RenderTarget target = offscreenTarget;
        if (target != null) {
            offscreenTarget = null;
            // GL buffer teardown must run on the render thread.
            if (RenderSystem.isOnRenderThread()) {
                target.destroyBuffers();
            } else {
                RenderSystem.recordRenderCall(target::destroyBuffers);
            }
        }
    }
}
