package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.mixin.client.LevelRendererEntityTargetAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FastColor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.slf4j.Logger;

/**
 * Chest Sense's own glow pass: the spectral-arrow outline (silhouettes drawn with vanilla's outline
 * render types, then edge, blur, blit), but run on a target of its own with a wider edge
 * ({@code shaders/program/chest_sense_edge.fsh}) so the line is bolder than a glowing mob's, and
 * vanilla's glow is left as it is.
 *
 * <p>Outline render types always draw into {@code LevelRenderer#entityTarget}, so between
 * {@link #begin} and {@link #endDraw} that field points at this pass's target; {@link #endDraw} puts it back.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ChestSenseGlow {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CHAIN =
        ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "shaders/post/chest_sense_outline.json");

    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1536));

    private static PostChain chain;
    private static RenderTarget target;
    private static int width = -1;
    private static int height = -1;
    /** Set when the effect failed to load, so it isn't retried every frame; cleared on a resource reload. */
    private static boolean failed;
    private static RenderTarget swappedOut;

    private ChestSenseGlow() {}

    /**
     * Readies the pass and points outline drawing at it; false (and nothing changed) when the effect
     * can't load. Every true must be followed by {@link #endDraw}, then {@link #present}.
     */
    static boolean begin(Minecraft mc) {
        LevelRendererEntityTargetAccessor lr = (LevelRendererEntityTargetAccessor) mc.levelRenderer;
        if (lr.dungeontrain$entityTarget() == null || !ensureChain(mc)) return false;
        target.clear(Minecraft.ON_OSX);
        mc.getMainRenderTarget().bindWrite(false);
        swappedOut = lr.dungeontrain$entityTarget();
        lr.dungeontrain$setEntityTarget(target);
        return true;
    }

    /** Where to draw a silhouette glowing {@code rgb}: outline render types only, everything else dropped. */
    static MultiBufferSource source(int rgb) {
        int argb = FastColor.ARGB32.color(255, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        return type -> {
            RenderType outline = type.isOutline() ? type : type.outline().orElse(null);
            return outline == null ? NoOpConsumer.INSTANCE : new Coloured(BUFFERS.getBuffer(outline), argb);
        };
    }

    /** Draws what was queued (under the caller's camera matrix) and puts the outline target back. */
    static void endDraw(Minecraft mc) {
        LevelRendererEntityTargetAccessor lr = (LevelRendererEntityTargetAccessor) mc.levelRenderer;
        try {
            BUFFERS.endBatch();
        } finally {
            lr.dungeontrain$setEntityTarget(swappedOut);
            swappedOut = null;
            mc.getMainRenderTarget().bindWrite(false);
        }
    }

    /** Runs the edge pass and lays the glow over the screen, as vanilla lays its entity glow. */
    static void present(Minecraft mc, float partialTick) {
        chain.process(partialTick);
        mc.getMainRenderTarget().bindWrite(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        target.blitToScreen(mc.getWindow().getWidth(), mc.getWindow().getHeight(), false);
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    private static boolean ensureChain(Minecraft mc) {
        if (failed) return false;
        int w = mc.getWindow().getWidth();
        int h = mc.getWindow().getHeight();
        if (chain == null) {
            try {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(), CHAIN);
                target = chain.getTempTarget("final");
                width = -1;
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Chest Sense glow could not load {}; outlines are off", CHAIN, e);
                close();
                failed = true;
                return false;
            }
        }
        if (w != width || h != height) {
            chain.resize(w, h);
            width = w;
            height = h;
        }
        return true;
    }

    private static void close() {
        if (chain != null) chain.close();
        chain = null;
        target = null;
        width = -1;
        height = -1;
    }

    /** Shaders are rebuilt on a resource reload: drop the pass so the next pulse loads it fresh. */
    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            RenderSystem.recordRenderCall(() -> {
                close();
                failed = false;
            });
        });
    }

    /** Forces one colour onto every vertex, as vanilla's outline generator does. */
    private record Coloured(VertexConsumer delegate, int argb) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z).setColor(argb);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    /** Swallows every vertex. */
    enum NoOpConsumer implements VertexConsumer {
        INSTANCE;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
