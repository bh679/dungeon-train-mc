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
 * target's colour attachment is registered with the texture manager under {@link #location()}.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ScreenToTexture {

    private static int nextId;

    /** This texture's name with the texture manager: one per instance, so several TVs can show at once. */
    private final ResourceLocation location;

    private final int guiWidth;
    private final int guiHeight;
    private final RenderTarget drawn;
    private final RenderTarget flipped;
    private Screen screen;
    private boolean registered;

    /**
     * A texture the size and shape of the window — the same GUI size and scale the screen was laid
     * out for, so what the TV shows is the frame the player was looking at, letterboxed by the TV
     * rather than re-laid-out for it. Cleared to {@code clearRgb} under the screen.
     */
    public ScreenToTexture(int clearRgb) {
        var window = Minecraft.getInstance().getWindow();
        this.guiWidth = Math.max(320, window.getGuiScaledWidth());
        this.guiHeight = Math.max(240, window.getGuiScaledHeight());
        int scale = Math.max(1, (int) Math.round(window.getGuiScale()));
        int w = guiWidth * scale, h = guiHeight * scale;
        this.drawn = new TextureTarget(w, h, true, Minecraft.ON_OSX);
        this.flipped = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        float r = ((clearRgb >> 16) & 0xFF) / 255f, g = ((clearRgb >> 8) & 0xFF) / 255f, b = (clearRgb & 0xFF) / 255f;
        drawn.setClearColor(r, g, b, 1f);
        flipped.setClearColor(r, g, b, 1f);
        this.location = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "home_tv/" + (nextId++));
        Minecraft.getInstance().getTextureManager().register(location, new RenderTargetTexture());
        registered = true;
    }

    public ResourceLocation location() {
        return location;
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
        Screen previous = this.screen;
        this.screen = next;
        if (previous != null && previous != next) previous.removed();
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
            Minecraft.getInstance().getTextureManager().release(location);
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
