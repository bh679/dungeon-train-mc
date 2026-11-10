package games.brennan.dungeontrain.client.logo;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * Draws the Dungeon Train logo on GUI screens, in two shapes from one tube:
 * <ul>
 *   <li>{@link #drawRing}: the logo itself, a square-section ring with a 180 degree twist cut into
 *       four segments. Only the cuts move, so the segments seem to slide and twist round the ring;
 *       the twist drifts too, and cracks regrow on any face that has turned fully away from the
 *       viewer. A port of the website's {@code logo-ring/logo-ring.js}, with Brennan's tuned look.</li>
 *   <li>{@link #drawBar}: the same tube unrolled into a straight line of carriages, the loading
 *       bar. Its front grows with the progress, a carriage at a time; the twist keeps rolling.</li>
 * </ul>
 *
 * <p>Each shape renders into its own {@link LogoCanvas} and is blitted into the GUI. One shared
 * instance; it frees its GPU memory once nothing has drawn it for a few seconds
 * ({@link #releaseIfIdle}).</p>
 */
public final class LogoRingRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double CUT_SPEED = Math.toRadians(30);
    private static final double FACE_ON = Math.toRadians(90 + 45);
    private static final double FACE_SPEED = Math.toRadians(-40);
    private static final Vector3f CAMERA_AT = new Vector3f(0.4f, -1.6f, 4.6f);
    private static final float FOV = (float) Math.toRadians(30);
    /** Edge lines: 3 px on the website's 540 px ring, kept in proportion at any size. */
    private static final float LINE_PER_PIXEL = 3f / 540f;
    private static final float FADE_IN_SECONDS = 0.8f;
    private static final long IDLE_RELEASE_NANOS = 5_000_000_000L;
    private static final int MAX_TARGET = 4096;

    /** Carriages in the loading bar. */
    public static final int BAR_CARRIAGES = 8;
    /** The bar's view: world height shown, and the margin round the line's ends. */
    private static final double BAR_VIEW_H = 0.75;
    private static final double BAR_MARGIN = 0.12;
    /** How fast the bar's front catches up with the progress it is given, per second. */
    private static final float BAR_CATCH_UP = 5f;

    /** Last of the statics: the constructor reads CAMERA_AT. */
    private static final LogoRingRenderer INSTANCE = new LogoRingRenderer();

    private ShaderInstance shader;
    private LogoCrackTexture cracks;
    private final LogoCanvas ringCanvas = new LogoCanvas();
    private final LogoCanvas barCanvas = new LogoCanvas();
    private final FaceVisibility visibility = new FaceVisibility(CAMERA_AT);
    private double offset;
    private double faceDrift;
    private long lastNanos;
    private float dt;
    private float shown;          // 0..1 fade-in once the cracks are ready
    private double barFront = -1; // the bar's shown progress, easing towards the real one
    private boolean shaderMissingLogged;

    private LogoRingRenderer() {}

    public static LogoRingRenderer get() { return INSTANCE; }

    void setShader(ShaderInstance loaded) { this.shader = loaded; }

    /**
     * Draws the ring into the GUI square at ({@code x}, {@code y}), {@code size} GUI units wide.
     *
     * @param alpha       0..1 opacity, for screens that fade
     * @param renderScale target pixels per real screen pixel (2 = supersampled, smoother edges)
     * @return false while it can't draw (shader missing, cracks still building)
     */
    public boolean drawRing(GuiGraphics g, float x, float y, float size, float alpha, float renderScale) {
        if (alpha <= 0f || size <= 0f) return false;
        int texture = prepare();
        if (texture < 0) return false;
        offset += CUT_SPEED * dt;
        double faceOn = faceOn();
        int[] regrow = visibility.newlyHidden(offset, faceOn);
        for (int piece = 0; piece < regrow.length; piece++) {
            if (regrow[piece] != 0) cracks.regrowHidden(piece, visibility.hidden(piece));
        }

        int px = pixels(size, renderScale);
        g.flush();
        Matrix4f view = new Matrix4f().lookAt(CAMERA_AT, new Vector3f(), new Vector3f(0f, 1f, 0f));
        Matrix4f projection = new Matrix4f().perspective(FOV, 1f, 0.1f, 50f);
        float segLen = (float) LogoRingGeometry.segmentLength();
        ringCanvas.render(shader, texture, px, px, view, projection, false, Math.max(1f, LINE_PER_PIXEL * px),
                sink -> LogoRingGeometry.build(offset, faceOn, sink.withSegLen(segLen)));
        ringCanvas.blit(g, x, y, size, size, alpha * shown);
        return true;
    }

    /**
     * The loading bar: the tube laid straight across {@code width} GUI units, centred on
     * {@code centreY}, its carriages filling in from the left as {@code progress} goes 0 to 1.
     *
     * @return false while it can't draw, so the caller can fall back to its own bar
     */
    public boolean drawBar(GuiGraphics g, float x, float centreY, float width, double progress, float alpha, float renderScale) {
        if (alpha <= 0f || width <= 0f) return false;
        int texture = prepare();
        if (texture < 0) return false;
        double target = Mth.clamp(progress, 0.0, 1.0);
        if (barFront < 0 || target < barFront - 0.05) barFront = target; // a new load starts over
        barFront += (target - barFront) * Math.min(1f, dt * BAR_CATCH_UP);

        double carLen = LogoRingGeometry.segmentLength(), gap = LogoRingGeometry.gapLength();
        double total = BAR_CARRIAGES * carLen + (BAR_CARRIAGES - 1) * gap;
        double viewW = total + 2 * BAR_MARGIN;
        float height = barHeight(width);
        int pxW = pixels(width, renderScale), pxH = Math.max(8, Math.round(pxW * height / width));

        g.flush();
        double front = barFront * total, faceOn = faceOn();
        Matrix4f view = new Matrix4f().lookAt(new Vector3f((float) (total / 2), -1.6f, 4.6f),
                new Vector3f((float) (total / 2), 0f, 0f), new Vector3f(0f, 1f, 0f));
        float halfW = (float) (viewW / 2), halfH = (float) (BAR_VIEW_H / 2);
        Matrix4f projection = new Matrix4f().ortho(-halfW, halfW, -halfH, halfH, 0.1f, 50f);
        float tubePx = (float) (pxH * 2 * LogoRingGeometry.HALF / BAR_VIEW_H);
        barCanvas.render(shader, texture, pxW, pxH, view, projection, true, Math.max(1f, 0.03f * tubePx), sink -> {
            for (int k = 0; k < BAR_CARRIAGES; k++) {
                double start = k * (carLen + gap), len = Math.min(carLen, front - start);
                if (len < 0.01) break;
                LogoRingGeometry.buildStraight(start, len, faceOn, k % LogoRingGeometry.PIECES, sink.withSegLen((float) len));
            }
        });
        barCanvas.blit(g, x, centreY - height / 2f, width, height, alpha * shown);
        return true;
    }

    /** The bar's height in GUI units for a given width: the view keeps the tube's proportions. */
    public static float barHeight(float width) {
        double carLen = LogoRingGeometry.segmentLength(), gap = LogoRingGeometry.gapLength();
        double viewW = BAR_CARRIAGES * carLen + (BAR_CARRIAGES - 1) * gap + 2 * BAR_MARGIN;
        return (float) (width * BAR_VIEW_H / viewW);
    }

    /** Frees the render targets and texture once nothing has drawn the logo for a while. Render thread. */
    public void releaseIfIdle() {
        if (lastNanos == 0 || System.nanoTime() - lastNanos < IDLE_RELEASE_NANOS) return;
        ringCanvas.release();
        barCanvas.release();
        if (cracks != null) { cracks.close(); cracks = null; }
        lastNanos = 0;
        shown = 0f;
        barFront = -1;
        visibility.reset();
    }

    /** Advances the clock (once per frame) and returns the crack texture, or -1 while it can't draw. */
    private int prepare() {
        if (shader == null) {
            if (!shaderMissingLogged) {
                LOGGER.warn("[DungeonTrain] logo_ring shader is not loaded; the logo is off");
                shaderMissingLogged = true;
            }
            return -1;
        }
        if (cracks == null) cracks = new LogoCrackTexture();
        long now = System.nanoTime();
        if (now != lastNanos) {
            dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            faceDrift += FACE_SPEED * dt;
        }
        int texture = cracks.textureId();
        if (texture < 0) return -1; // still building: fades in once it is ready
        shown = Math.min(1f, shown + dt / FADE_IN_SECONDS);
        return texture;
    }

    private double faceOn() { return FACE_ON + faceDrift; }

    private static int pixels(float guiSize, float renderScale) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return Mth.clamp(Math.round((float) (guiSize * guiScale * renderScale)), 16, MAX_TARGET);
    }
}
