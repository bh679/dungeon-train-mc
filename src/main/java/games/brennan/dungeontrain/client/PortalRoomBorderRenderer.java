package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.net.PortalRoomBorderPacket;
import games.brennan.dungeontrain.portal.PortalRoomBorderSpan;
import games.brennan.dungeontrain.portal.PortalSealPlane;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws a World Border dimensional carriage's border: vanilla's forcefield stripes on the four sides
 * of the room box, blue while the player is inside it, red while they are through it.
 *
 * <p><b>Vanilla's look, DT's wall.</b> The texture, the two colours, the additive blend, the
 * two-block stripe repeat and the three-second scroll are vanilla's {@code renderWorldBorder}. The
 * wall is not vanilla's: a level has one border, it pushes entities back and refuses blocks past it,
 * and every twin shares a dimension. This one has no collision at all — it is only a picture — so a
 * player walks through it and builds out past it in the clearance the room swept.</p>
 *
 * <p><b>Height.</b> Runs out to the twin seal on each side, which reads as infinite from inside the
 * room and never reaches the world — see {@link PortalRoomBorderSpan}.</p>
 *
 * <p><b>Who draws it.</b> Only a client holding a region from the server, which only sends one to a
 * player in or around a World Border room. Nobody on the train or in the world ever draws it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class PortalRoomBorderRenderer {

    private static final ResourceLocation FORCEFIELD =
        ResourceLocation.withDefaultNamespace("textures/misc/forcefield.png");

    /** Vanilla's stationary border colour. */
    private static final int BLUE = 0x20A0FF;
    /** Vanilla's shrinking border colour — what this one turns while a player is through it. */
    private static final int RED = 0xFF3030;

    /** How far the colour moves toward its target each tick — about five ticks end to end. */
    private static final float EASE_PER_TICK = 0.2f;

    /** Vanilla's stripe scroll period. */
    private static final long SCROLL_MILLIS = 3000L;

    private static final RenderType BORDER = States.border();

    /** 0 = blue, 1 = red. Only touched on the client thread. */
    private static float redness = 0.0f;

    private PortalRoomBorderRenderer() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        PortalRoomBorderPacket region = ClientPortalRoomBorder.region();
        LocalPlayer player = Minecraft.getInstance().player;
        if (!region.present() || player == null) {
            redness = 0.0f;
            return;
        }
        float target = isThrough(region, player.getBoundingBox()) ? 1.0f : 0.0f;
        redness += Mth.clamp(target - redness, -EASE_PER_TICK, EASE_PER_TICK);
    }

    /**
     * True when any part of the player's box is outside the room's walls — straddling a face is
     * already "through it", the moment vanilla's own border would have stopped them.
     */
    static boolean isThrough(PortalRoomBorderPacket region, AABB box) {
        return box.minX < region.minX() || box.maxX > region.maxX()
            || box.minZ < region.minZ() || box.maxZ > region.maxZ();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        PortalRoomBorderPacket region = ClientPortalRoomBorder.region();
        if (!region.present()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        int[] span = PortalRoomBorderSpan.of(ClientPortalSeal.cut(), twinCut(region),
            level.getMinBuildHeight(), level.getMaxBuildHeight(), region.minY(), region.maxY());

        Vec3 cam = event.getCamera().getPosition();
        double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        float scroll = (float) (Util.getMillis() % SCROLL_MILLIS) / (float) SCROLL_MILLIS;
        int rgb = lerpColour(BLUE, RED, redness);

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(BORDER);
        Matrix4f m = event.getPoseStack().last().pose();
        Wall wall = new Wall(vc, m, cam, span[0], span[1], scroll, rgb);

        // West and east faces run along Z; north and south along X.
        wall.drawX(region.minX(), region.minZ(), region.maxZ(), alpha(cam.x - region.minX(), reach));
        wall.drawX(region.maxX(), region.minZ(), region.maxZ(), alpha(region.maxX() - cam.x, reach));
        wall.drawZ(region.minZ(), region.minX(), region.maxX(), alpha(cam.z - region.minZ(), reach));
        wall.drawZ(region.maxZ(), region.minX(), region.maxX(), alpha(region.maxZ() - cam.z, reach));
        buffer.endBatch(BORDER);
    }

    /**
     * The seal cut of the room's own twin band, for a frame on which the live cut hides nothing —
     * seal culling switched off. The same planes {@code ClientPortalSeal} would hold a camera in this
     * room to, so the border still stops at the seal.
     */
    private static PortalSealPlane.Cut twinCut(PortalRoomBorderPacket region) {
        int lid = DungeonTrainCommonConfig.isUpsideDownBedrockRoof()
            ? ClientUpsideDownBand.roofY() : Integer.MAX_VALUE;
        return PortalSealPlane.cutForTwin(region.minY(), region.maxY() - 1,
            ClientUpsideDownBand.bedrockY(), lid);
    }

    /** Vanilla's fade: full strength up close, gone at the edge of the render distance. */
    private static float alpha(double distance, double reach) {
        double d = 1.0 - Math.abs(distance) / reach;
        return (float) Mth.clamp(Math.pow(Math.max(0.0, d), 4.0), 0.0, 1.0);
    }

    private static int lerpColour(int from, int to, float t) {
        int r = Mth.lerpInt(t, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
        int g = Mth.lerpInt(t, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
        int b = Mth.lerpInt(t, from & 0xFF, to & 0xFF);
        return (r << 16) | (g << 8) | b;
    }

    /** One frame's wall emitter: camera-relative vertices, so far twin coordinates keep precision. */
    private record Wall(VertexConsumer vc, Matrix4f m, Vec3 cam, int bottom, int top,
                        float scroll, int rgb) {

        /** A face at constant X, running from {@code z0} to {@code z1}. */
        void drawX(double x, double z0, double z1, float alpha) {
            if (alpha <= 0.0f) return;
            float rx = (float) (x - cam.x);
            quad(rx, (float) (z0 - cam.z), rx, (float) (z1 - cam.z), z0, z1, alpha);
        }

        /** A face at constant Z, running from {@code x0} to {@code x1}. */
        void drawZ(double z, double x0, double x1, float alpha) {
            if (alpha <= 0.0f) return;
            float rz = (float) (z - cam.z);
            quad((float) (x0 - cam.x), rz, (float) (x1 - cam.x), rz, x0, x1, alpha);
        }

        /**
         * One quad from {@code (ax, az)} to {@code (bx, bz)}, bottom to top. UVs repeat every two
         * blocks along the wall and up it, scrolled by time — vanilla's stripe.
         */
        private void quad(float ax, float az, float bx, float bz, double along0, double along1,
                          float alpha) {
            float y0 = (float) (bottom - cam.y);
            float y1 = (float) (top - cam.y);
            float u0 = scroll + (float) (along0 * 0.5);
            float u1 = scroll + (float) (along1 * 0.5);
            float v0 = scroll + (float) (Mth.frac(bottom * 0.5));
            float v1 = v0 + (top - bottom) * 0.5f;
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            int a = Mth.clamp((int) (alpha * 255.0f), 0, 255);
            vc.addVertex(m, ax, y0, az).setUv(u0, v1).setColor(r, g, b, a);
            vc.addVertex(m, bx, y0, bz).setUv(u1, v1).setColor(r, g, b, a);
            vc.addVertex(m, bx, y1, bz).setUv(u1, v0).setColor(r, g, b, a);
            vc.addVertex(m, ax, y1, az).setUv(u0, v0).setColor(r, g, b, a);
        }
    }

    /**
     * Bridge to {@link RenderStateShard}'s protected shards, as {@code MenuRenderStates} does.
     * Vanilla's border draws position+texture with the colour as a uniform; a buffered render type
     * has no uniform per batch, so the colour rides on the vertices instead.
     */
    private static final class States extends RenderStateShard {

        private States() {
            super("dungeontrain_portal_room_border_bridge", () -> {}, () -> {});
        }

        static RenderType border() {
            return RenderType.create(
                DungeonTrain.MOD_ID + ":portal_room_border",
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS, 256, false, true,
                RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(GameRenderer::getPositionTexColorShader))
                    .setTextureState(new TextureStateShard(FORCEFIELD, false, false))
                    // SRC_ALPHA, ONE: vanilla's additive border blend.
                    .setTransparencyState(LIGHTNING_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(COLOR_WRITE)
                    .setLayeringState(POLYGON_OFFSET_LAYERING)
                    .createCompositeState(false));
        }
    }
}
