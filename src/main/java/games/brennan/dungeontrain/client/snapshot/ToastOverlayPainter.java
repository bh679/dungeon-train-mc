package games.brennan.dungeontrain.client.snapshot;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL13;

/**
 * Paints the vanilla advancement toast — "Challenge Complete!" over the advancement's name, with
 * its icon — onto a finished photo, top-right, the way it sits on the player's screen. The framed
 * snapshot pass draws the level only, so the toast is added here, offscreen, afterwards.
 *
 * <p>Same offscreen recipe as {@code client/builder/BuildRenderCapture}: own {@link TextureTarget},
 * explicit ortho projection + model-view (this runs outside the GUI pass), no scissor, everything
 * restored in {@code finally}.</p>
 */
final class ToastOverlayPainter {

    /** The vanilla toast sprite and its GUI-pixel size. */
    private static final ResourceLocation TOAST_SPRITE = ResourceLocation.withDefaultNamespace("toast/advancement");
    private static final int TOAST_W = 160;
    private static final int TOAST_H = 32;
    /** The toast spans about this fraction of the photo's width. */
    private static final float TOAST_WIDTH_FRACTION = 0.34f;
    /** Vanilla's toast text colours: challenge pink, task/goal yellow, title white. */
    private static final int CHALLENGE_COLOUR = 0xFF88FF;
    private static final int TASK_COLOUR = 0xFFFF00;
    private static final int TITLE_COLOUR = 0xFFFFFF;
    private static final int MARGIN = 8;

    private ToastOverlayPainter() {}

    /**
     * {@code photo} with the toast for {@code display} drawn on. The caller owns and must close the
     * returned image; {@code photo} is left untouched. Render thread.
     */
    static NativeImage paint(Minecraft mc, NativeImage photo, DisplayInfo display) {
        int w = photo.getWidth();
        int h = photo.getHeight();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        RenderTarget target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        target.setClearColor(0f, 0f, 0f, 1f);
        DynamicTexture photoTexture = new DynamicTexture(copyOf(photo));
        ResourceLocation photoId = mc.getTextureManager().register("dt_milestone_photo", photoTexture);
        try {
            Matrix4f projection = new Matrix4f().setOrtho(0f, w, h, 0f, 1000f, ClientHooks.getGuiFarPlane());
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            target.clear(Minecraft.ON_OSX);   // clear() unbinds, so bind AFTER clearing
            target.bindWrite(true);
            GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            g.blit(photoId, 0, 0, 0f, 0f, w, h, w, h);
            drawToast(g, display, w);
            g.flush();
            NativeImage out = new NativeImage(w, h, false);
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(target.getColorTextureId());
            out.downloadTexture(0, false);
            out.flipY();
            return out;
        } finally {
            mc.getTextureManager().release(photoId);   // closes the texture and its image copy
            target.destroyBuffers();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** What {@code AdvancementToast.render} draws, scaled to the photo and pinned top-right. */
    private static void drawToast(GuiGraphics g, DisplayInfo display, int photoWidth) {
        float scale = photoWidth * TOAST_WIDTH_FRACTION / TOAST_W;
        float x = photoWidth - MARGIN - TOAST_W * scale;
        g.pose().pushPose();
        g.pose().translate(x, MARGIN, 0f);
        g.pose().scale(scale, scale, 1f);
        g.blitSprite(TOAST_SPRITE, 0, 0, TOAST_W, TOAST_H);
        boolean challenge = display.getType() == AdvancementType.CHALLENGE;
        g.drawString(Minecraft.getInstance().font, display.getType().getDisplayName(), 30, 7,
                challenge ? CHALLENGE_COLOUR : TASK_COLOUR, false);
        g.drawString(Minecraft.getInstance().font, display.getTitle(), 30, 18, TITLE_COLOUR, false);
        g.renderFakeItem(display.getIcon(), 8, 8);
        g.pose().popPose();
    }

    /** {@link DynamicTexture} takes ownership of its image, so hand it a copy and keep the original. */
    private static NativeImage copyOf(NativeImage src) {
        NativeImage copy = new NativeImage(src.format(), src.getWidth(), src.getHeight(), false);
        copy.copyFrom(src);
        return copy;
    }
}
