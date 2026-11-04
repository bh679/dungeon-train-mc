package games.brennan.dungeontrain.client.homeroom;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.ClientHooks;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * A {@link Screen} rendered into a texture instead of the window, so a Vista TV can show it.
 *
 * <p>The screen is never {@code mc.screen}: it is sized here ({@link Screen#init(Minecraft, int, int)}),
 * ticked here, and drawn once a frame into an off-screen target with the GUI pass's own orthographic
 * projection (the recipe {@code BuildRenderCapture} uses). A render target's rows run bottom-up, so
 * the picture is blitted into a second target the other way up before a TV samples it; the flipped
 * target's colour attachment is registered with the texture manager under {@link #LOCATION}.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ScreenToTexture {

    public static final ResourceLocation LOCATION = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "home_tv");

    /** GUI units tall. The recap screen lays out for at least 360; 2 px per unit keeps its text crisp. */
    public static final int GUI_HEIGHT = 360;
    public static final int PIXEL_SCALE = 2;

    private final int guiWidth;
    private final int guiHeight;
    private final RenderTarget drawn;
    private final RenderTarget flipped;
    private Screen screen;
    private boolean registered;

    /** A texture at {@code aspect} (width over height) tall enough for the recap, cleared to {@code clearRgb} under the screen. */
    public ScreenToTexture(float aspect, int clearRgb) {
        this.guiHeight = GUI_HEIGHT;
        this.guiWidth = Math.max(320, Math.round(GUI_HEIGHT * aspect));
        int w = guiWidth * PIXEL_SCALE, h = guiHeight * PIXEL_SCALE;
        this.drawn = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        this.flipped = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        float r = ((clearRgb >> 16) & 0xFF) / 255f, g = ((clearRgb >> 8) & 0xFF) / 255f, b = (clearRgb & 0xFF) / 255f;
        drawn.setClearColor(r, g, b, 1f);
        flipped.setClearColor(r, g, b, 1f);
        Minecraft.getInstance().getTextureManager().register(LOCATION, new RenderTargetTexture());
        registered = true;
    }

    public int guiWidth() {
        return guiWidth;
    }

    public int guiHeight() {
        return guiHeight;
    }

    public float aspect() {
        return guiWidth / (float) guiHeight;
    }

    public Screen screen() {
        return screen;
    }

    /** Make {@code next} the screen shown: sized to the texture, never to the window. */
    public void attach(Screen next) {
        this.screen = next;
        if (next != null) next.init(Minecraft.getInstance(), guiWidth, guiHeight);
    }

    /** Once a client tick, as the window would. */
    public void tick() {
        if (screen != null) screen.tick();
    }

    /** Draw the screen into the texture. Call before the level pass, with the main target not yet in use. */
    public void renderFrame(int mouseX, int mouseY, float partialTick) {
        if (screen == null) return;
        Minecraft mc = Minecraft.getInstance();
        Matrix4f savedProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            Matrix4f projection = new Matrix4f().setOrtho(0f, guiWidth, guiHeight, 0f, 1000f, ClientHooks.getGuiFarPlane());
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            drawn.clear(Minecraft.ON_OSX);
            drawn.bindWrite(true);
            GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            screen.render(graphics, mouseX, mouseY, partialTick);
            graphics.flush();
            flipInto();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    /** The drawn target, upside down, into the one a TV samples. */
    private void flipInto() {
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, drawn.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, flipped.frameBufferId);
        GL30C.glBlitFramebuffer(0, 0, drawn.width, drawn.height, 0, flipped.height, flipped.width, 0,
            GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    /** Let the screen go and free the targets. The texture location is released with them. */
    public void close() {
        Screen s = screen;
        screen = null;
        if (s != null) s.removed();
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(LOCATION);
            registered = false;
        }
        drawn.destroyBuffers();
        flipped.destroyBuffers();
    }

    /** The flipped target's colour attachment, as a texture the manager can hand out. Never loads or deletes anything. */
    private final class RenderTargetTexture extends AbstractTexture {
        @Override
        public int getId() {
            return flipped.getColorTextureId();
        }

        @Override
        public void load(ResourceManager manager) {}

        @Override
        public void releaseId() {}

        @Override
        public void close() {}
    }
}
