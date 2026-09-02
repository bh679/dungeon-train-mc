package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

/**
 * The second half of the skybox punch under a shader pack: turn every hole that is still
 * visible at the end of the frame back into <em>sky</em>.
 *
 * <h2>Why a second pass</h2>
 * <p>The {@code AFTER_SKY} punch writes each cube's true depth so terrain behind it never draws
 * there. Vanilla is happy with that — the sky pixels underneath stay untouched. A deferred shader
 * pack is not: its composite decides "sky or surface" from the depth buffer, and a pixel at the
 * cube's depth with nothing in the gbuffer shades as a black wall. What the pack needs is depth
 * {@code 1.0} at exactly the hole pixels that nothing nearer has covered since.</p>
 *
 * <h2>Stencil, not a custom shader</h2>
 * <p>Iris latches depth and colour writes <b>off</b> for any {@code ShaderInstance} it does not own
 * while the world is rendering, so a mod core shader cannot write depth here. Iris does, however,
 * attach the main render target's depth texture — the {@code DEPTH32F_STENCIL8} one
 * {@link SkyboxStencil#requestStencil()} asked for — to its own gbuffer framebuffers, and never
 * touches stencil state. So:</p>
 * <ol>
 *   <li><b>Mark</b> — redraw the cubes with the vanilla position shader (which Iris routes to the
 *       pack's {@code gbuffers_basic}, allowed to write), depth test {@code LEQUAL}, depth and
 *       colour writes off, stencil {@code REPLACE} on {@link #STILL_VISIBLE_BIT}. A pixel passes
 *       only where the cube is still the frontmost surface — anything drawn in front of it since
 *       the punch left a nearer depth.</li>
 *   <li><b>Reopen</b> — a full-screen quad at the far plane, depth func {@code ALWAYS}, depth
 *       writes on, colour off, stencil {@code EQUAL} that bit. Those pixels are now at depth 1.0
 *       and the pack paints its sky there.</li>
 * </ol>
 *
 * <p>Runs at {@code AFTER_BLOCK_ENTITIES}: after every opaque thing — terrain, Sable's carriages,
 * entities, block entities — and immediately before Iris' <em>deferred</em> pass and its
 * no-translucents depth copy. Packs draw their sky and volumetric clouds in deferred from the
 * depth as it stands then, so a hole reopened any later shows sky without the clouds around it
 * (measured on Complementary Unbound at {@code AFTER_WEATHER}). The price of going early is that
 * a translucent surface <em>behind</em> a skybox block draws through it, because the hole is at
 * the far plane by the time translucents render. Skybox blocks face open sky or void in practice,
 * so the clouds win. The depth mask is read at entry and restored on exit rather than assumed.</p>
 *
 * <p>Under a pack every variant shows the <em>pack's</em> sky for the pipeline currently
 * rendering — an End block inside an End-skied carriage shows the pack's End, an End block in the
 * plain overworld shows the pack's overworld. Per-variant skies are a vanilla-only luxury.</p>
 */
public final class SkyboxHoleReopen {

    /**
     * The stencil bit the mark pass sets. Kept out of the range {@code SkyboxSky#stencilRef()}
     * uses (1..7) so the two schemes could coexist in one buffer if they ever had to.
     */
    public static final int STILL_VISIBLE_BIT = 0x80;

    /**
     * Polygon offset for the mark pass. The cubes are drawn twice with the same matrices and
     * shader, so their depths should be bit-identical — this nudges the second draw a unit or two
     * nearer so the {@code LEQUAL} test does not hinge on that invariance holding on every driver.
     */
    private static final float MARK_OFFSET_FACTOR = -1.0F;
    private static final float MARK_OFFSET_UNITS = -2.0F;

    private SkyboxHoleReopen() {}

    /**
     * Mark the still-visible hole pixels and reopen them to the far plane.
     *
     * @param drawCubes draws every skybox cube on screen with whatever shader and state is current
     *                  — the same meshes the punch drew at {@code AFTER_SKY}
     */
    public static void run(Runnable drawCubes) {
        boolean depthMaskWas = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean probe = games.brennan.dungeontrain.client.ShaderDiagnostics.recording();
        float before = probe ? readCentreDepth() : 0.0F;
        try {
            // Mark: stencil the pixels where a cube face is still the frontmost surface.
            SkyboxStencil.beginMaskPass();
            RenderSystem.stencilMask(STILL_VISIBLE_BIT);
            RenderSystem.stencilFunc(GL11.GL_ALWAYS, STILL_VISIBLE_BIT, STILL_VISIBLE_BIT);
            RenderSystem.setShader(GameRenderer::getPositionShader);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.disableCull();
            RenderSystem.polygonOffset(MARK_OFFSET_FACTOR, MARK_OFFSET_UNITS);
            RenderSystem.enablePolygonOffset();
            drawCubes.run();
            RenderSystem.disablePolygonOffset();

            // Reopen: push the marked pixels to the far plane.
            SkyboxStencil.beginSkyPass();
            RenderSystem.stencilFunc(GL11.GL_EQUAL, STILL_VISIBLE_BIT, STILL_VISIBLE_BIT);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.depthMask(true);
            drawFarPlaneQuad();
            if (probe) {
                games.brennan.dungeontrain.client.ShaderDiagnostics.recordReopen(before, readCentreDepth());
            }
        } finally {
            SkyboxStencil.endStencil();
            RenderSystem.polygonOffset(0.0F, 0.0F);
            RenderSystem.disablePolygonOffset();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(depthMaskWas);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /**
     * The depth at the centre of the bound framebuffer. Diagnostics only: a readback stalls the
     * pipeline, so it runs solely while the F3+5 panel is open, where "did the reopen take?" is
     * exactly the question being asked.
     */
    private static float readCentreDepth() {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        java.nio.FloatBuffer out = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        GL11.glReadPixels(viewport[0] + viewport[2] / 2, viewport[1] + viewport[3] / 2, 1, 1,
            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out);
        return out.get(0);
    }

    /**
     * A screen-covering quad at NDC {@code z = 1}, drawn through identity projection and
     * model-view so the position shader passes the clip coordinates straight through. Culling is
     * off in the caller, so winding is irrelevant.
     */
    private static void drawFarPlaneQuad() {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            RenderSystem.setShader(GameRenderer::getPositionShader);
            BufferBuilder builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0F, -1.0F, 1.0F);
            builder.addVertex(1.0F, -1.0F, 1.0F);
            builder.addVertex(1.0F, 1.0F, 1.0F);
            builder.addVertex(-1.0F, 1.0F, 1.0F);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }
}
