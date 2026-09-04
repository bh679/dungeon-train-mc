package games.brennan.dungeontrain.client.skybox;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import games.brennan.dungeontrain.block.SkyboxSky;
import games.brennan.dungeontrain.client.NetherSkyRenderer;
import games.brennan.dungeontrain.client.NightSkyRenderer;
import games.brennan.dungeontrain.client.SunriseSkyRenderer;
import games.brennan.dungeontrain.client.UpsideDownSkyRenderer;
import games.brennan.dungeontrain.client.VoidSkyRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * The stencil mask that lets each skybox variant show its own sky.
 *
 * <h2>Why stencil</h2>
 * <p>Depth alone can't mark the holes. The punch writes each cube's <em>true</em> depth —
 * that's precisely what lets geometry in front of it still draw — so once terrain starts
 * rendering those pixels are indistinguishable from ordinary blocks. A second buffer is
 * needed to remember "this pixel is a hole, and it belongs to variant N". That's stencil.</p>
 *
 * <h2>The two passes</h2>
 * <p>Both run inside {@code RenderLevelStageEvent.AFTER_SKY}, in the gap between vanilla
 * drawing the sky and drawing any terrain:</p>
 * <ol>
 *   <li><b>Mask</b> — per variant, {@code stencilFunc(ALWAYS, ref, 0xFF)} with
 *       {@code stencilOp(…, REPLACE)}, drawing that variant's cubes depth-only. This is the
 *       existing punch, now also stamping a variant id.</li>
 *   <li><b>Sky</b> — per variant, {@code stencilFunc(EQUAL, ref, 0xFF)} with stencil writes
 *       off and depth writes off, then draw that variant's sky. It lands only on that
 *       variant's holes.</li>
 * </ol>
 *
 * <p>Vanilla's frame clear is {@code GL_COLOR|GL_DEPTH} only — it never clears stencil — so
 * {@link #beginMaskPass()} clears it itself. Forgetting that would leak last frame's mask.</p>
 *
 * <h2>Availability</h2>
 * <p>Minecraft's main render target has no stencil attachment by default;
 * {@link #requestStencil()} asks NeoForge for one at client setup. If it doesn't take,
 * {@link #isAvailable()} reports false and the caller degrades to the plain depth punch —
 * every block then shows the live sky, which is wrong for three of the four variants but is
 * still a coherent image rather than garbage.</p>
 */
public final class SkyboxStencil {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean drawingSurfaceSky = false;
    /** So the availability verdict is logged once, not once per frame. */
    private static boolean loggedAvailability = false;

    private SkyboxStencil() {}

    /**
     * Ask for a stencil attachment on the main render target.
     *
     * <p>NeoForge's {@code enableStencil()} re-creates the target's attachments immediately
     * (it calls {@code resize} itself), swapping the depth texture for a combined
     * {@code DEPTH32F_STENCIL8} one — so this is GL work and <b>must run on the render
     * thread</b>. Callers on a mod-loading thread have to enqueue it.</p>
     */
    public static void requestStencil() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            LOGGER.warn("[DungeonTrain] No main render target at skybox stencil setup; "
                + "per-variant skies will fall back to the live sky.");
            return;
        }
        target.enableStencil();
        LOGGER.info("[DungeonTrain] Skybox stencil requested; enabled={}", target.isStencilEnabled());
    }

    /**
     * Did we actually get a stencil buffer? Checked per frame; cheap.
     *
     * <p>Note this reports what NeoForge was <em>asked</em> for, not what the driver
     * delivered: {@code isStencilEnabled()} returns the flag {@code enableStencil()} set, and
     * a framebuffer that failed to allocate stencil bits would still answer true (the failure
     * shows up as a framebuffer-completeness error in the log instead). Combined
     * depth-stencil is universally supported in practice, so this is a caveat rather than an
     * expected failure.</p>
     *
     * <p>Logged once, because the answer silently decides whether the non-surface variants
     * show their own sky or quietly fall back to the live one — a difference that is easy to
     * misread as a bug in the sky sources themselves.</p>
     */
    public static boolean isAvailable() {
        RenderTarget target = Minecraft.getInstance().getMainRenderTarget();
        boolean available = target != null && target.isStencilEnabled();
        if (!loggedAvailability) {
            loggedAvailability = true;
            if (available) {
                LOGGER.info("[DungeonTrain] Skybox stencil available — per-variant skies active.");
            } else {
                LOGGER.warn("[DungeonTrain] Skybox stencil UNAVAILABLE — every skybox block will "
                    + "reveal the live sky instead of its own. Depth punch still works.");
            }
        }
        return available;
    }

    /**
     * True while {@link #drawSky} is re-running vanilla's {@code renderSky} for the
     * {@link SkyboxSky#SURFACE} variant.
     *
     * <p>Read by {@code LevelRendererVoidSkyMixin} to suppress two things during that second
     * call: the band sky overlays (which already painted in the real sky pass) and vanilla's
     * black void plane (whose removal is the entire point of the surface variant).</p>
     */
    public static boolean isDrawingSurfaceSky() {
        return drawingSurfaceSky;
    }

    /**
     * What the <em>currently bound</em> framebuffer has at its stencil attachment:
     * {@code "texture"}, {@code "renderbuffer"} or {@code "none"}.
     *
     * <p>Distinct from {@link #isAvailable()}, which only asks the main render target. Under Iris
     * the level renders into Iris' own framebuffers, and whether stencil works there depends on
     * Iris having attached the main target's combined depth-stencil texture — a fact to be read
     * off the GL object, not assumed. Must be called on the render thread with the level's
     * framebuffer bound, i.e. from inside a {@code RenderLevelStageEvent}.</p>
     */
    public static String boundFramebufferStencil() {
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
            GL30.GL_STENCIL_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == GL11.GL_TEXTURE) return "texture";
        if (type == GL30.GL_RENDERBUFFER) return "renderbuffer";
        return "none";
    }

    /** Clear stencil and configure it for the mask pass. Pair with {@link #endStencil()}. */
    public static void beginMaskPass() {
        RenderSystem.clearStencil(0);
        RenderSystem.clear(GL11.GL_STENCIL_BUFFER_BIT, Minecraft.ON_OSX);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        RenderSystem.stencilMask(0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
    }

    /**
     * Configure the stencil for a pass that stamps a single <em>bit</em>, leaving every other bit
     * of the buffer alone.
     *
     * <p>Unlike {@link #beginMaskPass()} this does not clear. That matters: the variant ids written
     * at {@code AFTER_SKY} are what the post-composite sky pass reads to tell one hole from another,
     * and clearing here would erase them halfway through the frame.</p>
     */
    public static void beginMarkPass(int bit) {
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        RenderSystem.stencilMask(bit);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, bit, bit);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
    }

    /**
     * Restrict drawing to one variant's holes that also survived to the end of the frame — variant
     * id in the low bits, {@code markBit} set. Read-only: pair with {@link #beginSkyPass()}.
     */
    public static void variantSkyRef(int stencilRef, int markBit) {
        RenderSystem.stencilFunc(GL11.GL_EQUAL, markBit | stencilRef, markBit | 0x0F);
    }

    /** Stamp subsequent draws with this variant's id wherever they pass the depth test. */
    public static void maskRef(int stencilRef) {
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, stencilRef, 0xFF);
    }

    /** Switch to read-only stencil for the sky pass: test, never write. */
    public static void beginSkyPass() {
        RenderSystem.stencilMask(0x00);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    /** Restrict subsequent draws to one variant's holes. */
    public static void skyRef(int stencilRef) {
        RenderSystem.stencilFunc(GL11.GL_EQUAL, stencilRef, 0xFF);
    }

    /** Leave stencil exactly as vanilla expects it: off, unmasked, no-op. */
    public static void endStencil() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        RenderSystem.stencilMask(0xFF);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    /**
     * Draw one variant's sky. The caller has already restricted the stencil to that variant's
     * pixels, so these draws are free to cover the screen.
     *
     * <p>Depth writes are off and the depth test is disabled: the sky must fill the hole
     * regardless of the depth the mask pass just wrote there, and must not disturb the depth
     * that keeps terrain behind the hole culled.</p>
     */
    public static void drawSky(SkyboxSky sky, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                               Camera camera, float partialTick) {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        switch (sky) {
            // LIVE draws nothing: the depth punch already left vanilla's own sky showing.
            case LIVE -> { }
            case SURFACE -> drawSurfaceSky(frustumMatrix, projectionMatrix, camera, partialTick);
            case END -> VoidSkyRenderer.renderAsSkySource(frustumMatrix);
            case NETHER -> NetherSkyRenderer.renderAsSkySource(frustumMatrix, camera);
            case UPSIDE_DOWN -> UpsideDownSkyRenderer.renderAsSkySource(frustumMatrix, partialTick);
            case NIGHT -> NightSkyRenderer.renderAsSkySource(frustumMatrix, partialTick);
            case SUNRISE -> SunriseSkyRenderer.renderAsSkySource(frustumMatrix, partialTick);
        }

        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
    }

    /**
     * The overworld sky as seen from above ground, at any Y — by re-running vanilla's own
     * {@code renderSky} with its void plane suppressed.
     *
     * <p>Re-running it, rather than reimplementing it, is what buys authentic sunrise
     * colours, sun, moon phase, star brightness and rain fade for a few lines. The
     * {@code isFoggy} argument is forced to {@code false} so the sky still appears when the
     * camera is underwater or in a boss fog — a window to the sky should show the sky.</p>
     *
     * <p>Fog: the {@code skyFogSetup} runnable reproduces vanilla's own, so the re-drawn sky
     * is fogged identically to the first pass. Anything it leaves behind is moot regardless —
     * {@code renderLevel} unconditionally reconfigures terrain fog on the line immediately
     * after the {@code AFTER_SKY} dispatch.</p>
     */
    private static void drawSurfaceSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                       Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.levelRenderer == null) return;

        float renderDistance = mc.gameRenderer.getRenderDistance();
        boolean foggy = mc.level.effects().isFoggyAt(Mth.floor(camera.getPosition().x), Mth.floor(camera.getPosition().z))
            || mc.gui.getBossOverlay().shouldCreateWorldFog();
        Runnable skyFogSetup =
            () -> FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_SKY, renderDistance, foggy, partialTick);

        // Vanilla sets this immediately before its own renderSky call; the sky and void-plane
        // vertex buffers read RenderSystem.getShader() rather than setting their own.
        RenderSystem.setShader(GameRenderer::getPositionShader);

        drawingSurfaceSky = true;
        try {
            mc.levelRenderer.renderSky(frustumMatrix, projectionMatrix, partialTick, camera, false, skyFogSetup);
        } finally {
            drawingSurfaceSky = false;
        }
    }
}
