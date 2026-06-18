package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.CinematicCameraController;
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
import org.slf4j.Logger;

/**
 * Captures a third-person ride photo by grabbing the <em>real</em> rendered
 * frame. For one frame the live camera is overridden to the snapshot pose
 * (third-person, so the player and the train are in view) and the world is
 * drawn by the normal render path — so terrain, the Sable carriages, and the
 * player all appear. The main framebuffer is then read back at the tail of
 * {@code renderLevel} (before the GUI) into a {@link DynamicTexture}.
 *
 * <p>The pose is computed <b>here, at render time</b> ({@link #beginLiveCapture}),
 * not by the director at tick time — a player riding a Sable ship reports far
 * sub-level coordinates during the tick but renders at the real world position,
 * so a tick-time pose would place the camera millions of blocks away (sky only).
 * The director just chooses the tag.</p>
 *
 * <p>Driven by {@code GameRendererSnapshotMixin}: HEAD arms the override
 * (consumed by {@code CameraCinematicMixin} via {@link #isCapturing()} /
 * {@link #capturePose()}); TAIL grabs + restores.</p>
 */
public final class RideSnapshotCapture {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Long-edge resolution stored shots are down-scaled to. */
    private static final int MAX_EDGE = 640;

    private static volatile boolean capturing = false;
    private static CinematicCameraController.Pose capturePose;
    private static SnapshotTag captureTag;
    private static CameraType savedCameraType;

    private static volatile SnapshotTag pendingTag;

    private RideSnapshotCapture() {}

    // ── Mixin hooks (camera override) ────────────────────────────────────
    public static boolean isCapturing() { return capturing; }
    public static CinematicCameraController.Pose capturePose() { return capturePose; }

    // ── Director API ─────────────────────────────────────────────────────
    /** Queue a shot of this tag; the pose is built at render time. */
    public static void request(SnapshotTag tag) {
        if (tag != null) pendingTag = tag;
    }

    public static boolean hasPending() { return pendingTag != null; }

    /** renderLevel HEAD: build the render-space pose and arm the override for this frame. */
    public static void beginLiveCapture(GameRenderer gr, DeltaTracker deltaTracker) {
        if (capturing || pendingTag == null) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;

        SnapshotTag tag = pendingTag;
        pendingTag = null;

        // Pose in render space (correct world coords). The render partial tick lets the
        // carriage-occlusion check transform against where Sable draws the carriage blocks
        // this frame. Null = too dark or no clear/unobstructed angle → skip; the director
        // requests again shortly (its global cool-down throttles retries).
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
        CinematicCameraController.Pose pose = SnapshotCamera.poseFor(level, tag, player, partialTick);
        if (pose == null) return;

        captureTag = tag;
        capturePose = pose;
        capturing = true;
        // Third-person camera type → detached view (no hand) at the player's normal FOV.
        savedCameraType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        gr.setRenderBlockOutline(false);
    }

    /** renderLevel TAIL: grab the just-rendered world, store it, then restore the view. */
    public static void finishLiveCapture(GameRenderer gr) {
        if (!capturing) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            NativeImage shot = downscale(full, MAX_EDGE);
            if (shot != full) full.close();   // downscale returns src as-is when already small
            DynamicTexture texture = new DynamicTexture(shot);
            ResourceLocation id = mc.getTextureManager().register("dungeontrain_ride", texture);
            ClientLevel level = mc.level;
            long tick = level != null ? level.getGameTime() : 0L;
            RideSnapshotGallery.add(
                    new RideSnapshot(id, captureTag, shot.getWidth(), shot.getHeight(), tick),
                    ClientDisplayConfig.getRideSnapshotMaxStored());
            RideSnapshotDirector.onCaptureCommitted(captureTag);
            LOGGER.debug("[DungeonTrain] Ride snapshot {} tag={} ({}x{}) gallery={}",
                    id, captureTag, shot.getWidth(), shot.getHeight(), RideSnapshotGallery.size());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Ride snapshot capture failed", e);
        } finally {
            capturing = false;
            if (savedCameraType != null) mc.options.setCameraType(savedCameraType);
            gr.setRenderBlockOutline(true);
        }
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
    }
}
