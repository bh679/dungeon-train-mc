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
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.client.ClientVoidWall;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.worldgen.VoidWallLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
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
 * The void wall, drawn as the real sky, to the pixel. At the end of the frame every pixel whose surface
 * lies past a wall is blended toward the sky that was behind it:
 *
 * <ul>
 *   <li><b>Past a standing wall</b>, fully. Chunk culling does most of the hiding, but it works in whole
 *       sections — a Distant Horizons LOD section off to the side can be thousands of blocks wide and
 *       reach back past the camera, so it is kept whole — and this catches whatever such a section lets
 *       through past the wall.</li>
 *   <li><b>Past a fading wall</b> ({@link VoidWallLayout.Result#hasVeil}), by its strength: nothing past
 *       it is culled, so the far side comes into view out of the actual skybox (the End sky in a void)
 *       rather than a flat fog colour — and at strength 1 this is exactly what a culled frame shows, so
 *       the hand-off has no seam.</li>
 * </ul>
 *
 * <h2>How</h2>
 * <ul>
 *   <li>{@code AFTER_SKY}: the colour buffer is copied — the sky, and nothing drawn over it yet.</li>
 *   <li>{@code AFTER_WEATHER}: the scene depth is copied, as {@link PostFogPass} does (under a shader
 *       pack it is copied from the main target at {@code AFTER_LEVEL} instead).</li>
 *   <li>{@code AFTER_LEVEL}: a full-screen quad rebuilds each pixel's camera-relative position from the
 *       vanilla depth, or from Distant Horizons' own depth texture where vanilla drew nothing (DH writes
 *       its LODs over the frame without touching vanilla depth), and paints the saved sky over it when
 *       it is past the wall and off the track corridor.</li>
 * </ul>
 *
 * <p><b>Under a shader pack</b> the sky is the pack's, drawn in its own passes, so there is no clean
 * copy of it to take. The pass instead copies the pack's finished frame at {@code AFTER_LEVEL} — the
 * first point a mod's own program may draw — and, for each pixel past a wall, walks up the screen to the
 * nearest pixel that is sky and uses that colour: a pack's sky varies mostly with height, so the column
 * above is its closest match. Failing that it walks down, and failing both uses the fog colour.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class VoidWallFadePass {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation SHADER_ID =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "void_wall_fade");
    private static final int GL13_TEXTURE0 = 0x84C0;
    /** Head-room over the carriage floor the corridor spares — the train and whatever rides on top. */
    private static final int CORRIDOR_HEADROOM = 24;
    /** Camera-relative X standing in for "no wall" in the shader. */
    private static final float NONE = 1.0e9F;

    private static ShaderInstance shader;

    private static final Target sky = new Target(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
    private static final Target depth = new Target(GL30.GL_DEPTH_COMPONENT32F, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);

    /** How recently DH must have published a frame for its depth to be trusted, in nanoseconds. */
    private static final long DH_FRESH_NANOS = 250_000_000L;

    /** Set this frame once the sky has been copied; the later stages draw only when it is. */
    private static boolean skyCaptured;
    private static boolean depthCaptured;
    /** Whether this frame is a shader pack's — the sky is then searched for in the finished frame. */
    private static boolean packFrame;

    // --- Distant Horizons: its depth texture and projection, published from DH's before-render event.
    private static volatile int dhDepthTexture = 0;
    private static volatile boolean dhReverseZ;
    private static volatile boolean dhZeroToOne;
    private static final Matrix4f dhInvProj = new Matrix4f();
    private static volatile double dhReach;
    private static volatile long dhPublishedAt;
    /** Under a pack: the projection Iris draws DH with, inverted. */
    private static final Matrix4f packDhInvProj = new Matrix4f();
    /** Whether the last pack frame could read DH's depth — read from DH's culling threads, hence volatile. */
    private static volatile boolean packDhReadable;

    private VoidWallFadePass() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(), SHADER_ID, DefaultVertexFormat.POSITION),
                loaded -> shader = loaded);
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] void_wall_fade shader failed to load; the void wall will cut instead of fade", e);
        }
    }

    /** Whether the sky pass can run — its shader loaded. It runs with or without a shader pack. */
    public static boolean available() {
        return shader != null;
    }

    /**
     * DH's depth texture, projection and view distance for this frame, from
     * {@code DistantHorizonsVoidWall}. The projection arrives inverted and column-major, ready for the
     * shader.
     */
    public static void setDistantHorizonsFrame(int depthTexture, Matrix4f inverseProjection,
                                               boolean reverseZ, boolean zeroToOne, double reach) {
        synchronized (dhInvProj) {
            dhInvProj.set(inverseProjection);
        }
        dhDepthTexture = depthTexture;
        dhReverseZ = reverseZ;
        dhZeroToOne = zeroToOne;
        dhReach = reach;
        dhPublishedAt = depthTexture != 0 ? System.nanoTime() : 0L;
    }

    /**
     * Whether DH is hidden by the sky pass under a shader pack — DH is drawing and Iris' projection for it
     * was found. When not, the pass cannot place DH's pixels and {@code ClientVoidWall} culls DH instead.
     */
    public static boolean readsDistantHorizonsUnderPack() {
        return packDhReadable;
    }

    private static boolean dhFresh() {
        long at = dhPublishedAt;
        return at != 0L && System.nanoTime() - at < DH_FRESH_NANOS;
    }

    /** Whether a wall is fading, or standing within sight — vanilla's, or DH's when it is drawing. */
    private static boolean wanted(Vec3 cam) {
        VoidWallLayout.Result wall = ClientVoidWall.result();
        if (wall.hasVeil()) return true;
        if (!wall.hasCull()) return false;
        double reach = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0 + 16.0;
        if (dhFresh()) reach = Math.max(reach, dhReach);
        return wall.cullX() - cam.x < reach;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            skyCaptured = false;
            depthCaptured = false;
            packFrame = ShaderCompat.active();
            if (!available() || !wanted(event.getCamera().getPosition())) return;
            // Under a pack the sky is the pack's, drawn later in its own passes — the finished frame is
            // copied at AFTER_LEVEL instead, and the shader finds the sky in it.
            skyCaptured = packFrame || copyBound(sky);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // Under a pack the framebuffer bound here is one of the pack's, often with no depth attachment,
            // so a copy silently fails and leaves the last good depth in place — a ghost of an old view.
            // The pack's scene depth is copied from the main target at AFTER_LEVEL instead.
            if (skyCaptured) depthCaptured = packFrame || copyBound(depth);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            boolean ready = skyCaptured && depthCaptured;
            skyCaptured = false;
            depthCaptured = false;
            if (ready && packFrame) {
                Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                ready = copyBound(sky) && copyBound(depth);
            }
            // Under a pack DH still fills its own depth texture, but draws it with Iris' projection (forward
            // Z) rather than the one it publishes. Without that projection DH is culled (ClientVoidWall).
            boolean dh = dhFresh();
            if (packFrame) {
                packDhReadable = available() && dh && IrisDhDepth.inverseProjection(packDhInvProj);
                dh = packDhReadable;
            }
            if (ready) draw(event, dh);
        }
    }

    /** Copy the bound framebuffer into {@code target}, (re)allocating on resize. */
    private static boolean copyBound(Target target) {
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getMainRenderTarget().width;
        int height = mc.getMainRenderTarget().height;
        if (width <= 0 || height <= 0) return false;
        target.copyFrom(width, height);
        return true;
    }

    private static void draw(RenderLevelStageEvent event, boolean dh) {
        VoidWallLayout.Result wall = ClientVoidWall.result();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (wall.isNone() || level == null || shader == null) return;
        Vec3 cam = event.getCamera().getPosition();
        int trainY = ClientUpsideDownBand.trainY();

        Matrix4f invProj = new Matrix4f(event.getProjectionMatrix()).invert();
        Matrix4f invView = new Matrix4f(event.getModelViewMatrix()).invert();
        shader.safeGetUniform("InvProj").set(invProj);
        shader.safeGetUniform("InvView").set(invView);
        if (packFrame) {
            shader.safeGetUniform("DhInvProj").set(packDhInvProj);
        } else {
            synchronized (dhInvProj) {
                shader.safeGetUniform("DhInvProj").set(dhInvProj);
            }
        }
        shader.safeGetUniform("HasDh").set(dh ? 1 : 0);
        // Iris draws DH with an ordinary perspective: forward Z, -1..1.
        shader.safeGetUniform("DhReverseZ").set(!packFrame && dhReverseZ ? 1 : 0);
        shader.safeGetUniform("DhZeroToOne").set(!packFrame && dhZeroToOne ? 1 : 0);
        shader.safeGetUniform("CullX").set(wall.hasCull() ? (float) (wall.cullX() - cam.x) : NONE);
        shader.safeGetUniform("VeilX").set(wall.hasVeil() ? (float) (wall.veilX() - cam.x) : NONE);
        shader.safeGetUniform("Strength").set((float) wall.veilStrength());
        shader.safeGetUniform("Corridor").set(
            (float) (trainY - 2 - cam.y), (float) (trainY + CORRIDOR_HEADROOM - cam.y),
            (float) (0 - cam.z), (float) (CarriageDims.DEFAULT_WIDTH - cam.z));
        shader.safeGetUniform("CloudY").set((float) (level.effects().getCloudHeight() - cam.y));
        shader.safeGetUniform("SearchSky").set(packFrame ? 1 : 0);
        shader.safeGetUniform("TexelY").set(1.0F / Math.max(1, mc.getMainRenderTarget().height));
        float[] fog = RenderSystem.getShaderFogColor();
        shader.safeGetUniform("FogColor").set(fog[0], fog[1], fog[2], 1.0F);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, sky.id);
        RenderSystem.setShaderTexture(1, depth.id);
        RenderSystem.setShaderTexture(2, dh ? dhDepthTexture : depth.id);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
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
    }

    /** A texture this pass owns, filled by copying the bound framebuffer. */
    private static final class Target {
        private final int internalFormat;
        private final int format;
        private final int type;
        private int id;
        private int width;
        private int height;

        Target(int internalFormat, int format, int type) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
        }

        void copyFrom(int w, int h) {
            if (id == 0) id = GL11.glGenTextures();
            RenderSystem.activeTexture(GL13_TEXTURE0);
            RenderSystem.bindTexture(id);
            if (w != width || h != height) {
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, (ByteBuffer) null);
                width = w;
                height = h;
            }
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        }
    }
}
