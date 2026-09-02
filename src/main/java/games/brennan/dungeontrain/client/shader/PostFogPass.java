package games.brennan.dungeontrain.client.shader;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientPortalCrossing;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Dimensional-carriage fog (and, optionally, the corridor lift) drawn <em>after</em> a shader
 * pack has finished the frame.
 *
 * <h2>Why after</h2>
 * <p>{@code PortalRoomFogEvents} narrows vanilla's fog planes, which is how an endless room's far
 * copies vanish into fog. A pack computes its own atmosphere and most of them ignore
 * {@code fogStart}/{@code fogEnd}, so under shaders the room's edge stands there in plain view.
 * The one place a pack cannot discard anything is after its own final pass: this class copies the
 * scene depth at {@code AFTER_WEATHER} (the last stage before Iris composites) and at
 * {@code AFTER_LEVEL} (after Iris' final pass, main framebuffer bound) draws a full-screen quad that
 * fogs by view distance toward the same fog colour the frame was set up with.</p>
 *
 * <h2>Why copy the depth</h2>
 * <p>Iris' final pass does not hand the scene depth back to the main framebuffer, and sampling a
 * depth texture attached to the framebuffer being drawn into is undefined anyway. One
 * {@code glCopyTexSubImage2D} into a texture this class owns sidesteps both; a
 * {@code DEPTH_COMPONENT32F} destination is legal from a combined depth-stencil source, where a
 * blit would demand identical formats.</p>
 *
 * <h2>Why a core shader is fine here and nowhere earlier</h2>
 * <p>Iris latches depth and colour writes off for any {@code ShaderInstance} it does not own while
 * the world is rendering. That lock lifts in {@code finalizeLevelRendering}, before
 * {@code AFTER_LEVEL} fires, so this is the first point in the frame where a mod's own program can
 * draw. The vanilla path never runs this: with no pack, vanilla's fog planes already do the job.</p>
 *
 * <p>The request/consume shape mirrors the frame: {@code PortalRoomFogEvents} publishes the planes it
 * applied on each {@code RenderFog}, the weather stage snapshots depth, colour and projection, and
 * {@code AFTER_LEVEL} draws once and clears. A frame the fog handler sits out draws nothing.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PostFogPass {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SHADER_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "post_fog");

    /**
     * The corridor lift as a screen-space brightening, as a fraction of the lightmap hold
     * {@code LightTexturePortalCrossingMixin} applies (0.55). Screen lift is stronger per unit than a
     * lightmap lift — it brightens already-lit pixels rather than the floor of the lightmap — so it
     * is scaled down rather than copied.
     */
    private static final float SCREEN_LIFT_SCALE = 0.35F;

    private static ShaderInstance shader;

    private static int depthTexture = 0;
    private static int depthWidth = 0;
    private static int depthHeight = 0;

    // --- Request: the fog planes the RenderFog handler applied this frame ------------------------
    private static boolean requested;
    private static float requestNear;
    private static float requestFar;

    // --- Capture: what the weather stage snapshotted for AFTER_LEVEL -------------------------------
    private static boolean captured;
    private static final Matrix4f invProj = new Matrix4f();
    private static final float[] fogColor = new float[4];
    private static float lift;

    /** What the last frame drew, for the diagnostics panel. Empty when it drew nothing. */
    private static volatile String lastDrawn = "";

    private PostFogPass() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(), SHADER_ID, DefaultVertexFormat.POSITION),
                loaded -> shader = loaded);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] post_fog shader failed to load; carriage fog under shader packs is off", e);
        }
    }

    /**
     * The fog planes {@code PortalRoomFogEvents} just applied. Called on every {@code RenderFog} it
     * cancels; the last call in a frame wins, which is the terrain fog.
     */
    public static void requestFog(float near, float far) {
        requested = true;
        requestNear = near;
        requestFar = far;
    }

    /** For the F3+5 panel: what the post pass drew last frame, or {@code ""}. */
    public static String lastDrawn() {
        return lastDrawn;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            capture(event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            draw();
        }
    }

    private static void capture(RenderLevelStageEvent event) {
        captured = false;
        if (!ShaderCompat.active() || shader == null) {
            requested = false;
            return;
        }
        float crossing = ClientDisplayConfig.isShaderCrossingLiftEnabled()
            ? Math.max(0.0F, Math.min(1.0F, ClientPortalCrossing.current())) : 0.0F;
        boolean wantsFog = requested && requestFar > 0.0F;
        requested = false;
        if (!wantsFog && crossing <= 0.0F) return;

        Minecraft mc = Minecraft.getInstance();
        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        if (width <= 0 || height <= 0) return;

        copyDepth(width, height);
        event.getProjectionMatrix().invert(invProj);
        float[] color = RenderSystem.getShaderFogColor();
        System.arraycopy(color, 0, fogColor, 0, 4);
        lift = crossing * SCREEN_LIFT_SCALE;
        // Fog planes: the request when there is one, otherwise "beyond everything" so only the
        // lift is applied.
        if (!wantsFog) {
            requestNear = Float.MAX_VALUE / 4;
            requestFar = Float.MAX_VALUE / 2;
        }
        captured = true;
    }

    /** Copy the bound framebuffer's depth into the pass' own texture, (re)allocating on resize. */
    private static void copyDepth(int width, int height) {
        if (depthTexture == 0) depthTexture = GL11.glGenTextures();
        RenderSystem.activeTexture(GL13_TEXTURE0);
        RenderSystem.bindTexture(depthTexture);
        if (width != depthWidth || height != depthHeight) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, width, height, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            depthWidth = width;
            depthHeight = height;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    }

    private static void draw() {
        if (!captured) {
            lastDrawn = "";
            return;
        }
        captured = false;
        if (shader == null) return;

        shader.getUniform("InvProj").set(invProj);
        shader.getUniform("FogColor").set(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        shader.getUniform("FogStart").set(requestNear);
        shader.getUniform("FogEnd").set(requestFar);
        shader.getUniform("Lift").set(lift);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, depthTexture);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            BufferBuilder builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            builder.addVertex(-1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, -1.0F, 0.0F);
            builder.addVertex(1.0F, 1.0F, 0.0F);
            builder.addVertex(-1.0F, 1.0F, 0.0F);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
        lastDrawn = requestFar < 1.0e6F
            ? String.format(java.util.Locale.ROOT, "fog %.1f..%.1f lift %.2f", requestNear, requestFar, lift)
            : String.format(java.util.Locale.ROOT, "lift %.2f", lift);
    }

    private static final int GL13_TEXTURE0 = 0x84C0;
}
