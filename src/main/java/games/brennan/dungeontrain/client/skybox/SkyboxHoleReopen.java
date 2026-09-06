package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
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
 *   <li><b>Reopen</b> — the cubes again with {@code glDepthRange(1, 1)}, depth func {@code ALWAYS},
 *       depth writes on, colour off, stencil {@code EQUAL} that bit. Those pixels are now at
 *       depth 1.0 and the pack paints its sky there.</li>
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
        // The punch ran at AFTER_SKY with an identity model-view; by AFTER_BLOCK_ENTITIES vanilla
        // has pushed the frustum matrix onto the stack for terrain (LevelRenderer#renderLevel,
        // right after the sky). The cube vertices already carry the frustum, so drawing them under
        // that stack rotated them twice and the mark landed nowhere — measured as stencil 0x00 at a
        // pixel squarely inside the hole. Identity for the duration, restored on the way out.
        org.joml.Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            // Mark: stencil the pixels where a cube face is still the frontmost surface.
            SkyboxStencil.beginMarkPass(STILL_VISIBLE_BIT);
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
            int marked = probe ? readCentreStencil() : -1;

            // Reopen: push the marked pixels to the far plane. The cubes are drawn a third time
            // with the depth range pinned to 1.0, so every fragment lands at the far plane whatever
            // its geometry says — the same draw that demonstrably wrote the punch, rather than a
            // hand-built clip-space quad whose fate under a pack's vertex shader is unknown.
            SkyboxStencil.beginSkyPass();
            RenderSystem.stencilFunc(GL11.GL_EQUAL, STILL_VISIBLE_BIT, STILL_VISIBLE_BIT);
            RenderSystem.depthFunc(GL11.GL_ALWAYS);
            RenderSystem.depthMask(true);
            GL11.glDepthRange(1.0, 1.0);
            try {
                drawCubes.run();
            } finally {
                GL11.glDepthRange(0.0, 1.0);
            }
            if (probe) {
                games.brennan.dungeontrain.client.ShaderDiagnostics.recordReopen(before, readCentreDepth(), marked);
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
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

    /** The stencil value at the centre pixel — is the mark pass landing? Diagnostics only. */
    private static int readCentreStencil() {
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        java.nio.IntBuffer out = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        GL11.glReadPixels(viewport[0] + viewport[2] / 2, viewport[1] + viewport[3] / 2, 1, 1,
            GL11.GL_STENCIL_INDEX, GL11.GL_UNSIGNED_INT, out);
        return out.get(0);
    }
}
