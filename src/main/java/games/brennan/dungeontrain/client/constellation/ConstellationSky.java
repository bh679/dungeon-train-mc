package games.brennan.dungeontrain.client.constellation;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.client.SkyDomeDraw;
import games.brennan.dungeontrain.client.perks.PerksScreen;
import games.brennan.dungeontrain.client.perks.SkyViewAccess;
import games.brennan.dungeontrain.client.tributeroom.ScreenToTexture;
import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

/**
 * The constellation patch's picture and its draw.
 *
 * <p>The picture is the Perks menu's own sky ({@link PerksScreen#forSky()}) drawn into a texture by
 * the tribute TV's {@link ScreenToTexture}, so what hangs in the sky is exactly what the menu shows:
 * the same stars, links, class titles and lit perks, and names only on hover. The crosshair is the
 * mouse: {@link #aim} turns the look direction into a point on the menu's canvas.</p>
 *
 * <p>The disc is the menu's square sky region (the map's 800 x 800 fit), mapped round onto
 * {@link ConstellationGeometry}. Its rim is drawn twice against the depth buffer so it can meet the
 * real sky and blocks differently ({@link ConstellationEdge}): pass A only where depth is still at the
 * far plane (sky), pass B only where something nearer was drawn (blocks). Both force the fragment
 * depth to the far plane with {@code glDepthRange(1, 1)} so the depth test does the sorting; depth is
 * never written.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ConstellationSky {

    private static final int SEGMENTS = 72;
    /** Rings across the feather, for a smooth falloff with per-vertex alpha. */
    private static final int FEATHER_RINGS = 8;
    /** Picture redraws at most this often while nothing changes (the menu twinkles and glows). */
    private static final long REDRAW_MS = 50;
    private static final int OFF = -10_000;

    private static ScreenToTexture texture;
    private static long lastDrawnMs = Long.MIN_VALUE;
    private static int mouseX = OFF, mouseY = OFF;
    private static int drawnMouseX = OFF - 1, drawnMouseY = OFF - 1;

    private ConstellationSky() {}

    /** Point the menu's mouse at where {@code look} meets the disc, or nowhere. */
    static void aim(Vector3f look) {
        if (texture == null) return;
        float[] hit = look == null ? null : ConstellationGeometry.hit(look);
        if (hit == null || hit[0] * hit[0] + hit[1] * hit[1] > 1f) {
            mouseX = OFF;
            mouseY = OFF;
            return;
        }
        float[] r = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        mouseX = Math.round(r[0] + hit[0] * r[2]);
        mouseY = Math.round(r[1] - hit[1] * r[2]);
    }

    /** Draw the picture if it is due. Frame start only, before the level pass. */
    static void refresh(float partialTick) {
        ScreenToTexture t = texture();
        long now = Util.getMillis();
        boolean moved = mouseX != drawnMouseX || mouseY != drawnMouseY;
        if (!moved && now - lastDrawnMs < REDRAW_MS && t.hasPicture()) return;
        t.renderFrame(mouseX, mouseY, partialTick);
        lastDrawnMs = now;
        drawnMouseX = mouseX;
        drawnMouseY = mouseY;
    }

    static void tick() {
        if (texture != null) texture.tick();
    }

    /** Let the picture go: leaving a world, or the patch has closed. */
    static void release() {
        if (texture != null) texture.close();
        texture = null;
        lastDrawnMs = Long.MIN_VALUE;
        mouseX = mouseY = OFF;
    }

    /** Something wants the picture: the patch has been open, or a window block asked for it. */
    static boolean wanted() {
        return texture != null;
    }

    static boolean hasPicture() {
        return texture != null && texture.hasPicture();
    }

    private static ScreenToTexture texture() {
        if (texture == null) {
            texture = new ScreenToTexture(PerksScreen.SKY_BG & 0xFFFFFF);
            texture.attach(PerksScreen.forSky());
        }
        return texture;
    }

    /**
     * The patch, over the finished level, at {@code alpha}: pass A over the real sky, pass B over blocks.
     *
     * @param view the level's camera-rotation matrix ({@code RenderLevelStageEvent#getModelViewMatrix})
     */
    static void drawPatch(Matrix4f view, float alpha, ConstellationEdge edge) {
        if (!hasPicture() || alpha <= 0f) return;
        try {
            GL11.glDepthRange(1.0, 1.0);
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.depthFunc(GL11.GL_LEQUAL); // stored depth 1.0: the real sky
            drawDisc(view, alpha, edge.softOverSky());
            RenderSystem.depthFunc(GL11.GL_GREATER); // stored depth nearer: blocks
            drawDisc(view, alpha, edge.softOverBlocks());
        } finally {
            GL11.glDepthRange(0.0, 1.0);
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
        }
    }

    /**
     * The window block's sky: the night dome with the patch on it at full strength. Drawn into the
     * stencil-masked hole, depth test off, by {@code SkyboxStencil}.
     */
    public static void renderAsSkySource(Matrix4f view) {
        SkyDomeDraw.fillDome(view, PerksScreen.SKY_BG & 0xFFFFFF, 1.0F);
        if (!hasPicture()) {
            // Seen through a window before anyone has stood in a block: make the picture next frame.
            texture();
            return;
        }
        RenderSystem.disableDepthTest();
        drawDisc(view, 1f, true);
    }

    /** One disc: a fan of rings, the texture's sky square on it, alpha falling off at the rim. */
    private static void drawDisc(Matrix4f view, float alpha, boolean soft) {
        float[] sq = SkyViewAccess.skySquare(texture.guiWidth(), texture.guiHeight());
        float gw = texture.guiWidth(), gh = texture.guiHeight();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture.location());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        float[] radii = ringRadii(soft);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int ring = 0; ring < radii.length - 1; ring++) {
            float r0 = radii[ring], r1 = radii[ring + 1];
            float a0 = alpha * ConstellationEdge.alphaAt(Math.min(r0, 0.9999f), soft);
            float a1 = alpha * ConstellationEdge.alphaAt(r1 >= 1f ? (soft ? 1f : 0.9999f) : r1, soft);
            for (int s = 0; s < SEGMENTS; s++) {
                double t0 = 2 * Math.PI * s / SEGMENTS, t1 = 2 * Math.PI * (s + 1) / SEGMENTS;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0);
                float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                if (r0 == 0f) {
                    vertex(bb, view, 0f, 0f, sq, gw, gh, a0);
                } else {
                    vertex(bb, view, r0 * c0, r0 * s0, sq, gw, gh, a0);
                    vertex(bb, view, r1 * c0, r1 * s0, sq, gw, gh, a1);
                    vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
                    vertex(bb, view, r0 * c0, r0 * s0, sq, gw, gh, a0);
                    vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
                    vertex(bb, view, r0 * c1, r0 * s1, sq, gw, gh, a0);
                    continue;
                }
                vertex(bb, view, r1 * c0, r1 * s0, sq, gw, gh, a1);
                vertex(bb, view, r1 * c1, r1 * s1, sq, gw, gh, a1);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
    }

    /** Centre, the start of the feather, rings through it, the rim. Hard: centre and rim only. */
    static float[] ringRadii(boolean soft) {
        if (!soft) return new float[] {0f, 1f};
        float[] r = new float[FEATHER_RINGS + 2];
        r[0] = 0f;
        for (int i = 0; i <= FEATHER_RINGS; i++) {
            r[i + 1] = ConstellationEdge.FEATHER_START + (1f - ConstellationEdge.FEATHER_START) * i / FEATHER_RINGS;
        }
        return r;
    }

    private static void vertex(BufferBuilder bb, Matrix4f view, float x, float y, float[] sq, float gw, float gh, float a) {
        Vector3f p = ConstellationGeometry.point(x, y);
        float u = (sq[0] + x * sq[2]) / gw;
        float v = (sq[1] - y * sq[2]) / gh;
        bb.addVertex(view, p.x, p.y, p.z).setUv(u, v).setColor(SkyDomeDraw.argb(a, 0xFFFFFF));
    }
}
