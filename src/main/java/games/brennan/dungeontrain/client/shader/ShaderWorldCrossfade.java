package games.brennan.dungeontrain.client.shader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

/**
 * Fades a shader pack from one of its worlds to another.
 *
 * <p>A pipeline swap is binary: Iris renders the frame with one program set. So while a band or a
 * dimensional carriage is mid-ramp, the level is rendered <b>twice</b> — once reporting the world
 * being left, once the world being entered — and the first image is blended over the second at the
 * ramp's weight. Both pipelines keep rendering every frame through the fade, so their temporal
 * state (TAA, exposure) stays warm; outside the fade window there is one render as normal.</p>
 *
 * <p>The blend happens immediately after the second render returns, still inside
 * {@code GameRenderer.renderLevel}: Iris' final pass has run, the main framebuffer is bound, and its
 * shader overrides and write-lock are off, so the vanilla {@code position_tex} shader draws the
 * saved image straight over the frame.</p>
 *
 * <p>The vanilla path never enters here — with no pack, Dungeon Train's own sky overlays and
 * lightmap fades already cross-fade the bands.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ShaderWorldCrossfade {

    /**
     * Frames still to render behind the loading screen with the pre-warmed Nether and End pipelines.
     * Compiling a pipeline is only half its first-use cost: Iris' {@code beginLevelRendering} also
     * calls {@code LevelRenderer.allChanged()} — a full chunk rebuild — the first frame each pipeline
     * renders. Paying that while the view area is still empty is free; paying it mid-fade is a hitch.
     */
    private static int warmupFramesLeft = 0;

    private static int colorTexture = 0;
    private static int colorWidth = 0;
    private static int colorHeight = 0;

    private ShaderWorldCrossfade() {}

    /**
     * Wraps the one {@code LevelRenderer.renderLevel} call in {@code GameRenderer.renderLevel}.
     * Called by {@code GameRendererCrossfadeMixin}.
     */
    public static void render(LevelRenderer levelRenderer, DeltaTracker deltaTracker, boolean renderBlockOutline,
                              Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                              Matrix4f frustumMatrix, Matrix4f projectionMatrix, Operation<Void> original) {
        if (!ShaderCompat.active()) {
            ShaderWorld.setReporting(null);
            ShaderWorld.recordFrame(new ShaderWorld.Blend(ShaderWorld.World.OVERWORLD, ShaderWorld.World.OVERWORLD, 0.0f), 1);
            original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
            return;
        }

        if (warmupFramesLeft > 0) {
            ShaderWorld.World warm = warmupFramesLeft == 2 ? ShaderWorld.World.NETHER : ShaderWorld.World.END;
            warmupFramesLeft--;
            ShaderWorld.setReporting(warm);
            original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
            ShaderWorld.setReporting(null);
            return;
        }

        ShaderWorld.Blend blend = ShaderWorld.decide(camera.getPosition().x);
        boolean fade = blend.fading() && ClientDisplayConfig.isShaderCrossfadeEnabled();
        if (!fade) {
            ShaderWorld.setReporting(blend.settled());
            ShaderWorld.recordFrame(blend, 1);
            original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
            return;
        }

        // Render the world being left, keep the image.
        ShaderWorld.setReporting(blend.from());
        original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        boolean saved = width > 0 && height > 0;
        if (saved) copyMainColor(width, height);

        // Render the world being entered, then lay the saved image over it at (1 - w).
        ShaderWorld.setReporting(blend.to());
        original.call(levelRenderer, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, frustumMatrix, projectionMatrix);
        if (saved) blitSaved(1.0f - blend.w());
        ShaderWorld.recordFrame(blend, 2);
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Compile the pack's Nether and End pipelines while the loading screen is still up, so the
        // first band entry does not stall mid-fade.
        ShaderWorld.prewarm();
        warmupFramesLeft = ShaderCompat.active() ? 2 : 0;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ShaderWorld.reset();
        warmupFramesLeft = 0;
    }

    private static void copyMainColor(int width, int height) {
        if (colorTexture == 0) colorTexture = GL11.glGenTextures();
        RenderSystem.activeTexture(GL13_TEXTURE0);
        RenderSystem.bindTexture(colorTexture);
        if (width != colorWidth || height != colorHeight) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            colorWidth = width;
            colorHeight = height;
        }
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
    }

    /** Draw the saved frame over the current one at {@code alpha}, through identity matrices. */
    private static void blitSaved(float alpha) {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, colorTexture);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            BufferBuilder builder = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            builder.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F);
            builder.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F);
            builder.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
            builder.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
            BufferUploader.drawWithShader(builder.buildOrThrow());
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    private static final int GL13_TEXTURE0 = 0x84C0;
}
