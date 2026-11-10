package games.brennan.dungeontrain.client.logo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import java.util.function.Consumer;

/**
 * One offscreen picture of the logo tube: its own render target with depth, drawn with the
 * {@code logo_ring} shader plus a black outline shell, then blitted into the GUI. The ring and the
 * loading bar each keep one, so neither resizes the other's.
 */
final class LogoCanvas {

    /** Segment length to vertex units: {@code logo_ring.vsh} divides UV2.x by the same. */
    static final float SEG_LEN_SCALE = 30000f;
    /** The along direction's angle to vertex units (UV2.y): {@code logo_ring.vsh} divides by the same. */
    static final double TANGENT_SCALE = 10000.0;

    /** Feeds a pass its geometry; {@link #withSegLen} names the length of the segment that follows. */
    interface Emitter {
        LogoRingGeometry.Sink withSegLen(float segLen);
    }

    private RenderTarget target;

    /**
     * Renders the geometry {@code build} emits (called once for the surface, once for the outline).
     *
     * @param lineWidth the edge lines, in target pixels
     */
    void render(ShaderInstance shader, int texture, int w, int h, Matrix4f view, Matrix4f projection,
                float lineWidth, Consumer<Emitter> build) {
        Minecraft mc = Minecraft.getInstance();
        if (target == null) {
            target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
            target.setClearColor(0f, 0f, 0f, 0f);
        } else if (target.width != w || target.height != h) {
            target.resize(w, h, Minecraft.ON_OSX);
        }

        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            modelView.identity().mul(view);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.disableBlend();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();

            // The segments, then the outline shell's inside faces behind them.
            shader.safeGetUniform("LineWidth").set(lineWidth);
            shader.safeGetUniform("Atlas").set((float) LogoCracks.ATLAS_W, (float) LogoCracks.ATLAS_ROW,
                    (float) LogoCracks.ATLAS_LEN, (float) (LogoRingGeometry.PIECES * 4));
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, texture);
            BufferBuilder surface = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.BLOCK);
            build.accept(segLen -> {
                int len = Math.round(segLen * SEG_LEN_SCALE);
                return (vx, vy, vz, nx, ny, nz, face, along, across, hx, hy, hz, piece, tangent) ->
                        surface.addVertex((float) vx, (float) vy, (float) vz).setColor(face, piece, 0, 255)
                                .setUv((float) along, (float) across).setUv2(len, (int) Math.round(tangent * TANGENT_SCALE))
                                .setNormal((float) nx, (float) ny, (float) nz);
            });
            MeshData surfaceMesh = surface.build();
            if (surfaceMesh == null) return; // nothing to draw yet (an empty loading bar): the cleared target stays blank
            BufferUploader.drawWithShader(surfaceMesh);

            RenderSystem.setShader(GameRenderer::getPositionShader);
            RenderSystem.setShaderColor(0f, 0f, 0f, 1f);
            GL11.glCullFace(GL11.GL_FRONT);
            BufferBuilder hull = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION);
            float o = (float) LogoRingGeometry.OUTLINE;
            build.accept(segLen -> (vx, vy, vz, nx, ny, nz, face, along, across, hx, hy, hz, piece, tangent) ->
                    hull.addVertex((float) (vx + hx * o), (float) (vy + hy * o), (float) (vz + hz * o)));
            MeshData hullMesh = hull.build();
            if (hullMesh != null) BufferUploader.drawWithShader(hullMesh);
        } finally {
            GL11.glCullFace(GL11.GL_BACK);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** The finished picture into a GUI rectangle (the target's rows run bottom-up, hence the flipped v). */
    void blit(GuiGraphics g, float x, float y, float w, float h, float alpha) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, target.getColorTextureId());
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        Matrix4f pose = g.pose().last().pose();
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        quad.addVertex(pose, x, y, 0f).setUv(0f, 1f);
        quad.addVertex(pose, x, y + h, 0f).setUv(0f, 0f);
        quad.addVertex(pose, x + w, y + h, 0f).setUv(1f, 0f);
        quad.addVertex(pose, x + w, y, 0f).setUv(1f, 1f);
        BufferUploader.drawWithShader(quad.buildOrThrow());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }

    void release() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }
}
