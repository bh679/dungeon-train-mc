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
 *       bar. Its front grows with the progress, a carriage at a time; the twist keeps rolling. At
 *       100% the train drives off up the screen, loops and draws in to the logo ({@link BarMorph}).</li>
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

    /** The progress that reads 100% (the real bar is capped just under 1). */
    private static final double FINISHED = 0.995;
    /** The bar's view margin round the line's ends, in world units. */
    private static final double BAR_MARGIN = 0.12;
    /** How long the title and percentage take to fade once the train pulls away. */
    private static final double FINISH_FADE_SECONDS = 0.8;
    /** How fast the bar's front catches up with the progress it is given, per second. */
    private static final float BAR_CATCH_UP = 5f;

    /** Last of the statics: the constructor reads CAMERA_AT. */
    private static final LogoRingRenderer INSTANCE = new LogoRingRenderer();

    private ShaderInstance shader;
    private LogoCrackTexture cracks;
    private final LogoCanvas ringCanvas = new LogoCanvas();
    private final LogoCanvas barCanvas = new LogoCanvas();
    private final LogoCanvas fadeCanvas = new LogoCanvas(); // a finish's fading carriages, on their own layer
    private final FaceVisibility visibility = new FaceVisibility(CAMERA_AT);
    private double offset;        // the ring's cuts
    private double ringDrift;     // the ring's face drift
    private double barDrift;      // the bar's (and its logo's) face drift
    private double barSpin;       // the bar's logo's cuts, once it has drawn in
    private double morphTime = -1; // seconds since the bar read 100%, -1 while loading
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
     * @param speed       how fast it turns, 1 = the website's pace
     * @return false while it can't draw (shader missing, cracks still building)
     */
    public boolean drawRing(GuiGraphics g, float x, float y, float size, float alpha, float renderScale, float speed) {
        if (alpha <= 0f || size <= 0f) return false;
        int texture = prepare();
        if (texture < 0) return false;
        offset += CUT_SPEED * dt * speed;
        ringDrift += FACE_SPEED * dt * speed;
        double faceOn = FACE_ON + ringDrift;
        int[] regrow = visibility.newlyHidden(offset, faceOn);
        for (int piece = 0; piece < regrow.length; piece++) {
            if (regrow[piece] != 0) cracks.regrowHidden(piece, visibility.hidden(piece));
        }

        int px = pixels(size, renderScale);
        g.flush();
        Matrix4f view = new Matrix4f().lookAt(CAMERA_AT, new Vector3f(), new Vector3f(0f, 1f, 0f));
        Matrix4f projection = new Matrix4f().perspective(FOV, 1f, 0.1f, 50f);
        float segLen = (float) LogoRingGeometry.segmentLength();
        ringCanvas.render(shader, texture, px, px, view, projection, Math.max(1f, LINE_PER_PIXEL * px),
                sink -> LogoRingGeometry.build(offset, faceOn, sink.withSegLen(segLen)));
        ringCanvas.blit(g, x, y, size, size, alpha * shown);
        return true;
    }

    /**
     * The loading bar: the tube laid straight across {@code width} GUI units, centred on
     * {@code centreY}, its carriages filling in from the left as {@code progress} goes 0 to 1.
     * Once it reads 100% the train drives off along its track, loops and draws in to the logo
     * ({@link BarMorph}); it draws over the whole screen so it can leave the bar's area.
     *
     * @return false while it can't draw, so the caller can fall back to its own bar
     */
    public boolean drawBar(GuiGraphics g, float x, float centreY, float width, double progress, float alpha, float renderScale) {
        if (alpha <= 0f || width <= 0f) return false;
        int texture = prepare();
        if (texture < 0) return false;
        double target = Mth.clamp(progress, 0.0, 1.0);
        if (barFront < 0 || target < barFront - 0.05) { barFront = target; morphTime = -1; } // a new load starts over
        barFront += (target - barFront) * Math.min(1f, dt * BAR_CATCH_UP);
        barDrift += FACE_SPEED * dt;
        if (morphTime < 0 && target >= FINISHED && barFront >= FINISHED - 0.01) {
            morphTime = 0;
            barSpin = 0; // each finish turns from where it closes, not where the last load's logo stopped
        }
        else if (morphTime >= 0) {
            morphTime += dt;
            barSpin += CUT_SPEED * dt * BarMorph.finish().spinRamp(morphTime);
        }

        // The whole screen is the view, so the train can leave the loading area: the bar's world
        // units map to GUI units at the bar's own scale, its line on centreY, its left end at x
        // (plus the margin). The camera keeps the ring's tilt, so heights come out a touch shorter.
        double t = morphTime;
        int screenW = g.guiWidth(), screenH = g.guiHeight();
        double k = width / (BarMorph.TOTAL + 2 * BAR_MARGIN), x0 = x + BAR_MARGIN * k;
        double cos = Math.cos(Math.atan2(1.6, 4.6));
        double lookX = (screenW / 2.0 - x0) / k, lookY = (centreY - screenH / 2.0) / k / cos;
        Matrix4f view = new Matrix4f().lookAt(new Vector3f((float) lookX, (float) lookY - 1.6f, 4.6f),
                new Vector3f((float) lookX, (float) lookY, 0f), new Vector3f(0f, 1f, 0f));
        float halfW = (float) (screenW / (2 * k)), halfH = (float) (screenH / (2 * k));
        Matrix4f projection = new Matrix4f().ortho(-halfW, halfW, -halfH, halfH, 0.1f, 50f);
        int pxW = pixels(screenW, renderScale), pxH = pixels(screenH, renderScale);
        float tubePx = (float) (pxW / (double) screenW * k * 2 * LogoRingGeometry.HALF);
        double front = barFront, drift = FACE_ON + barDrift, spin = barSpin;
        g.flush();
        barCanvas.render(shader, texture, pxW, pxH, view, projection, Math.max(1f, 0.03f * tubePx),
                sink -> BarMorph.emit(t, front, drift, spin, sink));
        BarMorph.Finish finish = BarMorph.finish();
        if (t >= 0 && finish.fadeAlpha(t) > 0) {
            fadeCanvas.render(shader, texture, pxW, pxH, view, projection, Math.max(1f, 0.03f * tubePx),
                    sink -> finish.emitFading(t, drift, sink));
            fadeCanvas.blit(g, 0, 0, screenW, screenH, alpha * shown * finish.fadeAlpha(t));
        }
        barCanvas.blit(g, 0, 0, screenW, screenH, alpha * shown);
        if (t >= 0) drawPortals(g, finish.portals(t), x0, centreY, k * cos, alpha * shown);
        return true;
    }

    /** The finish's portals, as glowing vertical lines, world (x, y) mapped to the GUI (heights foreshortened by the camera's tilt). */
    private static void drawPortals(GuiGraphics g, java.util.List<BarMorph.PortalLine> lines, double x0, double centreY,
                                    double ky, float alpha) {
        double kx = ky / Math.cos(Math.atan2(1.6, 4.6));
        for (BarMorph.PortalLine p : lines) {
            float gx = (float) (x0 + p.x() * kx);
            float bottom = (float) (centreY - (p.y() - p.height() / 2) * ky);
            float top = (float) (bottom - p.height() * ky * p.grown());
            int a = Math.round(255 * alpha);
            fill(g, gx - 3f, top - 1f, gx + 3f, bottom + 1f, (Math.round(a * 0.25f) << 24) | 0x9A6CFF);  // glow
            fill(g, gx - 1.5f, top, gx + 1.5f, bottom, (Math.round(a * 0.55f) << 24) | 0xB794FF);
            fill(g, gx - 0.5f, top, gx + 0.5f, bottom, (a << 24) | 0xF1E8FF);                          // core
        }
    }

    /** A filled rectangle at fractional GUI coordinates. */
    private static void fill(GuiGraphics g, float x1, float y1, float x2, float y2, int argb) {
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0);
        g.pose().scale((x2 - x1) / 1000f, (y2 - y1) / 1000f, 1);
        g.fill(0, 0, 1000, 1000, argb);
        g.pose().popPose();
    }

    /**
     * 0 while the bar loads, rising to 1 as the train pulls away at 100%: screens fade their title
     * and percentage out with it, since the train's loop runs through them.
     */
    public float barFinish() { return morphTime < 0 ? 0f : (float) Mth.clamp(morphTime / FINISH_FADE_SECONDS, 0.0, 1.0); }

    /** Frees the render targets and texture once nothing has drawn the logo for a while. Render thread. */
    public void releaseIfIdle() {
        if (lastNanos == 0 || System.nanoTime() - lastNanos < IDLE_RELEASE_NANOS) return;
        ringCanvas.release();
        barCanvas.release();
        fadeCanvas.release();
        if (cracks != null) { cracks.close(); cracks = null; }
        lastNanos = 0;
        shown = 0f;
        barFront = -1;
        morphTime = -1;
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
        }
        int texture = cracks.textureId();
        if (texture < 0) return -1; // still building: fades in once it is ready
        shown = Math.min(1f, shown + dt / FADE_IN_SECONDS);
        return texture;
    }

    private static int pixels(float guiSize, float renderScale) {
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        return Mth.clamp(Math.round((float) (guiSize * guiScale * renderScale)), 16, MAX_TARGET);
    }
}
