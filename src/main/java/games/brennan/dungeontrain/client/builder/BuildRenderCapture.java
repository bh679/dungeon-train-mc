package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL13;
import org.slf4j.Logger;

/**
 * A picture of a relay build, taken for the Discord post that announces its submission.
 *
 * <p>The same baked mesh the My Builds tile draws ({@link RelayBuildPreviews}), drawn once more into
 * an offscreen {@value #SIZE}px target with a transparent clear and read back as PNG bytes — the
 * recipe {@code TradeValueIconDump} uses for item icons. The tile renderer scissors to its cell in
 * <em>window</em> coordinates, which would clip an offscreen target of a different size, so this
 * draws through the unclipped variant.</p>
 *
 * <p>Never the reason a submit fails: no mesh yet (the tile has not been drawn), a driver that
 * renders offscreen targets black, or anything thrown here answers {@code null}, and the server
 * posts the announcement without a picture.</p>
 */
@OnlyIn(Dist.CLIENT)
public final class BuildRenderCapture {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Pixels per edge — big enough to read the build in a Discord embed, small on the wire. */
    static final int SIZE = 512;
    /** The tile's own three-quarter turn, so the picture matches what the author sees. */
    private static final float YAW = 35f;
    /** Share of the square the model fills; no border to saw through, so most of it. */
    private static final float FILL = 0.9f;

    private BuildRenderCapture() {}

    /**
     * PNG bytes of this relay build as it is now, or null when there is nothing to draw or the
     * capture failed. Render thread only.
     */
    public static byte[] png(int relayId) {
        if (!RenderSystem.isOnRenderThread()) return null;
        BuilderTileMesh mesh = RelayBuildPreviews.mesh(relayId);
        if (mesh == null) return null;
        try {
            return capture(Minecraft.getInstance(), mesh);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Build render for relay build {} failed: {}", relayId, t.toString());
            return null;
        }
    }

    /** Draws the mesh into a fresh offscreen target and reads it back; restores GL state after. */
    private static byte[] capture(Minecraft mc, BuilderTileMesh mesh) throws java.io.IOException {
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        RenderTarget target = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);
        target.setClearColor(0f, 0f, 0f, 0f);
        try {
            // The GUI pass's own ortho projection and model-view, at one unit per pixel, so the
            // tile renderer's transform lands the model where it would on screen. A button press
            // runs outside that pass, so both are set here rather than assumed.
            Matrix4f projection = new Matrix4f().setOrtho(0f, SIZE, SIZE, 0f, 1000f, ClientHooks.getGuiFarPlane());
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            // clear() unbinds the target when it is done, so bind AFTER clearing.
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            BuilderTileModelRenderer.renderUnclipped(graphics, mesh, 0, 0, SIZE, SIZE, YAW, FILL);
            graphics.flush();
            try (NativeImage image = new NativeImage(SIZE, SIZE, false)) {
                RenderSystem.activeTexture(GL13.GL_TEXTURE0);
                RenderSystem.bindTexture(target.getColorTextureId());
                image.downloadTexture(0, false);
                image.flipY();
                return image.asByteArray();
            }
        } finally {
            target.destroyBuffers();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }
}
