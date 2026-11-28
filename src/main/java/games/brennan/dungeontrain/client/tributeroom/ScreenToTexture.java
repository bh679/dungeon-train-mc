package games.brennan.dungeontrain.client.tributeroom;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.neoforge.client.ClientHooks;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * A {@link Screen} rendered into a texture instead of the window, so a Vista TV can show it.
 *
 * <p>The screen is never {@code mc.screen}: it is sized here ({@link Screen#init(Minecraft, int, int)}),
 * ticked here, and drawn into an off-screen target with the GUI pass's own orthographic projection
 * (the recipe {@code BuildRenderCapture} uses). Every TV draws into one shared window-sized scratch
 * target in turn (window-sized because {@code GuiGraphics.enableScissor} maps through the window's
 * scale and height), and a render target's rows run bottom-up, so the picture is then blitted the
 * other way up into this TV's own colour target, which may be smaller than the window
 * ({@code maxScale}). That target is registered with the texture manager under {@link #location()}.</p>
 *
 * <p>Drawing is on demand: {@link #drawIfDue} asks {@link TvRedrawPolicy}, fed by {@link #markSeen}
 * (Vista asked for the picture) and {@link #markDirty} (the page changed). A TV unseen for a while lets
 * its target go ({@link #releaseIfIdle}); the next draw makes it again.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ScreenToTexture {

    private static int nextId;
    /** Live instances, so the shared scratch target goes with the last of them. */
    private static int instances;
    /** The one window-sized target (with depth) every TV draws its screen into before the flip. */
    @Nullable private static RenderTarget scratch;
    /** Screens drawn and own targets alive, for the {@code [tributetv]} perf line. */
    private static int draws;
    /** Every screen drawn into a TV since the client started, for a harness to difference. */
    static long totalDraws;
    private static int liveTargets;
    /** Dev harness switch: draw every TV every frame, as before on-demand drawing, for a before/after. */
    static boolean alwaysDrawForDev;

    /** This texture's name with the texture manager: one per instance, so several TVs can show at once. */
    private final ResourceLocation location;

    private final int guiWidth;
    private final int guiHeight;
    /** The scratch draw's size in pixels: the window's. */
    private final int drawWidth;
    private final int drawHeight;
    /** This TV's own target's size in pixels. */
    private final int outWidth;
    private final int outHeight;
    private final float clearR, clearG, clearB;
    @Nullable private RenderTarget flipped;
    private Screen screen;
    private boolean registered;
    private long lastSeenMs = Long.MIN_VALUE;
    private long lastDrawnMs = Long.MIN_VALUE;
    private boolean dirty = true;
    /**
     * Force the picture's alpha to 1 after each draw. GUI blending also blends the alpha channel, so a
     * translucent draw (a glow) leaves the target less than opaque there; a TV ignores that, but a
     * picture drawn with blending over something else (the constellation patch) would show through.
     */
    private boolean opaque;

    private final Matrix4f savedProjection = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    @Nullable private GuiGraphics graphics;

    /** Full window resolution, for a picture that is also drawn over the whole window. */
    public ScreenToTexture(int clearRgb) {
        this(clearRgb, Integer.MAX_VALUE);
    }

    /**
     * A texture the size and shape of the window — the same GUI size and scale the screen was laid
     * out for, so what the TV shows is the frame the player was looking at, letterboxed by the TV
     * rather than re-laid-out for it. Its pixels are capped at {@code maxScale} per GUI unit. Cleared
     * to {@code clearRgb} under the screen.
     */
    public ScreenToTexture(int clearRgb, int maxScale) {
        var window = Minecraft.getInstance().getWindow();
        this.guiWidth = Math.max(320, window.getGuiScaledWidth());
        this.guiHeight = Math.max(240, window.getGuiScaledHeight());
        int scale = Math.max(1, (int) Math.round(window.getGuiScale()));
        int outScale = Math.max(1, Math.min(scale, maxScale));
        this.drawWidth = guiWidth * scale;
        this.drawHeight = guiHeight * scale;
        this.outWidth = guiWidth * outScale;
        this.outHeight = guiHeight * outScale;
        this.clearR = ((clearRgb >> 16) & 0xFF) / 255f;
        this.clearG = ((clearRgb >> 8) & 0xFF) / 255f;
        this.clearB = (clearRgb & 0xFF) / 255f;
        this.location = ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_tv/" + (nextId++));
        Minecraft.getInstance().getTextureManager().register(location, new RenderTargetTexture());
        registered = true;
        instances++;
    }

    /** See {@link #opaque}. Returns this. */
    public ScreenToTexture opaque() {
        this.opaque = true;
        return this;
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
        dirty = true;
    }

    /** Vista asked for this picture: the TV is being drawn. Called mid level pass, so only a stamp. */
    public void markSeen() {
        lastSeenMs = Util.getMillis();
    }

    /** The page changed (a click, a page turn): the next seen frame draws it. */
    public void markDirty() {
        dirty = true;
    }

    /** Whether there is a picture to show: drawn at least once since its target was made. */
    public boolean hasPicture() {
        return flipped != null && lastDrawnMs != Long.MIN_VALUE;
    }

    /** Once a client tick, as the window would. */
    public void tick() {
        if (screen != null) screen.tick();
    }

    /**
     * Draw the screen into the texture if the TV is seen and its picture is due ({@link TvRedrawPolicy}).
     * Call before the level pass, with the main target not yet in use.
     *
     * @param aimed      the crosshair is on the picture
     * @param distanceSq squared distance from the camera to the TV
     */
    public void drawIfDue(int mouseX, int mouseY, float partialTick, boolean aimed, double distanceSq) {
        if (screen == null) return;
        long now = Util.getMillis();
        if (alwaysDrawForDev || TvRedrawPolicy.shouldDraw(now, lastSeenMs, lastDrawnMs, dirty, aimed, distanceSq)) {
            renderFrame(mouseX, mouseY, partialTick);
        }
    }

    /** Draw the screen into the texture now. Call before the level pass, with the main target not yet in use. */
    public void renderFrame(int mouseX, int mouseY, float partialTick) {
        if (screen == null) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget into = scratch(drawWidth, drawHeight);
        RenderTarget out = ownTarget();
        savedProjection.set(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        boolean drawnOk = false;
        try {
            projection.setOrtho(0f, guiWidth, guiHeight, 0f, 1000f, ClientHooks.getGuiFarPlane());
            RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
            modelView.translation(0f, 0f, 10000f - ClientHooks.getGuiFarPlane());
            RenderSystem.applyModelViewMatrix();
            RenderSystem.enableDepthTest();
            into.setClearColor(clearR, clearG, clearB, 1f);
            into.clear(Minecraft.ON_OSX);
            into.bindWrite(true);
            if (graphics == null) graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            screen.render(graphics, mouseX, mouseY, partialTick);
            graphics.flush();
            flipInto(into, out);
            drawnOk = true;
        } finally {
            // A render that threw may have left the pose stack unbalanced: start the next one afresh.
            if (!drawnOk) graphics = null;
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
        lastDrawnMs = Util.getMillis();
        dirty = false;
        draws++;
        totalDraws++;
    }

    /** The scratch picture, upside down (and scaled down to this TV's size), into the target a TV samples. */
    private void flipInto(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        int filter = to.width == drawWidth && to.height == drawHeight ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
        GL30C.glBlitFramebuffer(0, 0, drawWidth, drawHeight, 0, to.height, to.width, 0,
            GL11C.GL_COLOR_BUFFER_BIT, filter);
        if (opaque) {
            GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, to.frameBufferId);
            RenderSystem.colorMask(false, false, false, true);
            RenderSystem.clearColor(0f, 0f, 0f, 1f);
            RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.colorMask(true, true, true, true);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
    }

    /** Unseen long enough: let this TV's target go (the screen and its state stay). */
    public void releaseIfIdle() {
        if (flipped != null && !alwaysDrawForDev && TvRedrawPolicy.shouldRelease(Util.getMillis(), lastSeenMs)) releaseTarget();
    }

    private RenderTarget ownTarget() {
        if (flipped == null) {
            flipped = new TextureTarget(outWidth, outHeight, false, Minecraft.ON_OSX);
            flipped.setClearColor(clearR, clearG, clearB, 1f);
            liveTargets++;
        }
        return flipped;
    }

    private void releaseTarget() {
        if (flipped == null) return;
        flipped.destroyBuffers();
        flipped = null;
        lastDrawnMs = Long.MIN_VALUE;
        dirty = true;
        liveTargets--;
    }

    private static RenderTarget scratch(int width, int height) {
        if (scratch == null) {
            scratch = new TextureTarget(width, height, true, Minecraft.ON_OSX);
            liveTargets++;
        } else if (scratch.width != width || scratch.height != height) {
            // Only after a window resize between two TVs being made: each draws at its own size.
            scratch.resize(width, height, Minecraft.ON_OSX);
        }
        return scratch;
    }

    /** Let the screen go and free the targets. The texture location is released with them. */
    public void close() {
        Screen s = screen;
        screen = null;
        if (s != null) s.removed();
        if (registered) {
            Minecraft.getInstance().getTextureManager().release(location);
            registered = false;
            instances--;
        }
        releaseTarget();
        graphics = null;
        if (instances <= 0 && scratch != null) {
            scratch.destroyBuffers();
            scratch = null;
            liveTargets--;
            instances = 0;
        }
    }

    /** The {@code [tributetv]} perf line's numbers: screens drawn since the last call, and targets alive. */
    static int[] drainCounters() {
        int[] out = {draws, liveTargets};
        draws = 0;
        return out;
    }

    /** This TV's colour target, as a texture the manager can hand out. Never loads or deletes anything. */
    private final class RenderTargetTexture extends AbstractTexture {
        @Override
        public int getId() {
            RenderTarget t = flipped;
            return t == null ? 0 : t.getColorTextureId();
        }

        @Override
        public void load(ResourceManager manager) {}

        @Override
        public void releaseId() {}

        @Override
        public void close() {}
    }
}
