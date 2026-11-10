package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

/**
 * Draws the Dungeon Train ring logo into a square on a GUI screen: a square-section ring with a
 * 180 degree twist, cut into four segments. Only the cuts move, so the segments seem to slide and
 * twist round the ring; the twist itself drifts too. Cracks regrow on any face that has turned fully
 * away from the viewer, so each face comes back round looking different. A port of the website's
 * {@code logo-ring/logo-ring.js}, with Brennan's tuned look.
 *
 * <p>The ring is drawn into its own render target (with depth, at {@code renderScale} times the
 * square's real pixels, so 2 gives smooth edges) and that image is blitted into the GUI. One shared
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
    /** Last of the statics: the constructor reads CAMERA_AT. */
    private static final LogoRingRenderer INSTANCE = new LogoRingRenderer();

    private ShaderInstance shader;
    private LogoCrackTexture cracks;
    private RenderTarget target;
    private final FaceVisibility visibility = new FaceVisibility(CAMERA_AT);
    private double offset;
    private double faceDrift;
    private long lastNanos;
    private float shown;          // 0..1 fade-in once the cracks are ready
    private boolean shaderMissingLogged;

    private LogoRingRenderer() {}

    public static LogoRingRenderer get() { return INSTANCE; }

    void setShader(ShaderInstance loaded) { this.shader = loaded; }

    /**
     * Draws the ring into the GUI square at ({@code x}, {@code y}), {@code size} GUI units wide.
     *
     * @param alpha       0..1 opacity, for screens that fade
     * @param renderScale target pixels per real screen pixel (2 = supersampled, smoother edges)
     */
    public void draw(GuiGraphics g, float x, float y, float size, float alpha, float renderScale) {
        if (alpha <= 0f || size <= 0f) return;
        if (shader == null) {
            if (!shaderMissingLogged) {
                LOGGER.warn("[DungeonTrain] logo_ring shader is not loaded; the logo ring is off");
                shaderMissingLogged = true;
            }
            return;
        }
        if (cracks == null) cracks = new LogoCrackTexture();
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;

        int texture = cracks.textureId();
        if (texture < 0) return; // still building: fades in once it is ready
        shown = Math.min(1f, shown + dt / FADE_IN_SECONDS);

        offset += CUT_SPEED * dt;
        faceDrift += FACE_SPEED * dt;
        double faceOn = FACE_ON + faceDrift;
        int[] regrow = visibility.newlyHidden(offset, faceOn);
        for (int piece = 0; piece < regrow.length; piece++) {
            if (regrow[piece] != 0) cracks.regrowHidden(piece, visibility.hidden(piece));
        }

        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int px = Mth.clamp(Math.round((float) (size * guiScale * renderScale)), 16, MAX_TARGET);
        g.flush();
        renderRing(px, texture, faceOn);
        blit(g, x, y, size, alpha * shown);
    }

    /** Frees the render target and texture once nothing has drawn the ring for a while. Render thread. */
    public void releaseIfIdle() {
        if (lastNanos == 0 || System.nanoTime() - lastNanos < IDLE_RELEASE_NANOS) return;
        if (target != null) { target.destroyBuffers(); target = null; }
        if (cracks != null) { cracks.close(); cracks = null; }
        lastNanos = 0;
        shown = 0f;
        visibility.reset();
    }

    private void renderRing(int px, int texture, double faceOn) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null) {
            target = new TextureTarget(px, px, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
            target.setClearColor(0f, 0f, 0f, 0f);
        } else if (target.width != px) {
            target.resize(px, px, Minecraft.ON_OSX);
        }

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            modelView.identity().lookAt(CAMERA_AT, new Vector3f(), new Vector3f(0f, 1f, 0f));
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(new Matrix4f().perspective(FOV, 1f, 0.1f, 50f), VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();

            // The segments, then the outline shell's inside faces behind them.
            shader.safeGetUniform("LineWidth").set(Math.max(1f, LINE_PER_PIXEL * px));
            shader.safeGetUniform("SegLen").set((float) LogoRingGeometry.segmentLength());
            shader.safeGetUniform("Atlas").set((float) LogoCracks.ATLAS_W, (float) LogoCracks.ATLAS_ROW,
                    (float) LogoCracks.ATLAS_LEN, (float) (LogoRingGeometry.PIECES * 4));
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, texture);
            BufferBuilder surface = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            LogoRingGeometry.build(offset, faceOn, (vx, vy, vz, nx, ny, nz, face, along, across, hx, hy, hz, piece) ->
                    surface.addVertex((float) vx, (float) vy, (float) vz).setColor(face, piece, 0, 255)
                            .setUv((float) along, (float) across).setLight(0).setNormal((float) nx, (float) ny, (float) nz));
            BufferUploader.drawWithShader(surface.buildOrThrow());

            RenderSystem.setShader(GameRenderer::getPositionShader);
            RenderSystem.setShaderColor(0f, 0f, 0f, 1f);
            GL11.glCullFace(GL11.GL_FRONT);
            BufferBuilder hull = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            float o = (float) LogoRingGeometry.OUTLINE;
            LogoRingGeometry.build(offset, faceOn, (vx, vy, vz, nx, ny, nz, face, along, across, hx, hy, hz, piece) ->
                    hull.addVertex((float) (vx + hx * o), (float) (vy + hy * o), (float) (vz + hz * o)));
            BufferUploader.drawWithShader(hull.buildOrThrow());
        } finally {
            GL11.glCullFace(GL11.GL_BACK);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** The finished ring image into the GUI square (the target's rows run bottom-up, hence the flipped v). */
    private void blit(GuiGraphics g, float x, float y, float size, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        quad.addVertex(pose, x, y, 0f).setUv(0f, 1f);
        quad.addVertex(pose, x, y + size, 0f).setUv(0f, 0f);
        quad.addVertex(pose, x + size, y + size, 0f).setUv(1f, 0f);
        quad.addVertex(pose, x + size, y, 0f).setUv(1f, 1f);
        BufferUploader.drawWithShader(quad.buildOrThrow());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
