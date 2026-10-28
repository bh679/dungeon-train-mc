package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.wind.EndFrostbite;
import games.brennan.dungeontrain.wind.WindZones;
import games.brennan.dungeontrain.worldgen.SpheresComets;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/**
 * Scenery comets: each client streaks its own down the far sky of the spheres band,
 * {@value SpheresComets#DISTANT_PER_REAL} for every real one ({@link SpheresComets#distantChancePerTick}). They are
 * never near the train and touch nothing: no packets, sound, shake or explosion, and no particles, just the
 * spinning magma head and one thin glowing streak behind it. Each keeps falling at full size until it is well below
 * the camera and out of sight ({@value #GONE_BELOW} blocks down). Like stars, they are pinned to the sky around the camera rather than to the
 * world, so walking or riding never brings one nearer or makes it grow. Render-thread state only.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class DistantComets {

    private static final int MAX_LIVE = 12;
    /** Where one starts: this far out from the camera, and this far above it. */
    private static final double OUT_MIN = 150.0, OUT_SPAN = 200.0;
    private static final double UP_MIN = 80.0, UP_SPAN = 60.0;
    /** Blocks per tick: across, and down. */
    private static final double DRIFT_MIN = 0.5, DRIFT_SPAN = 0.7;
    private static final double FALL_MIN = 1.4, FALL_SPAN = 0.6;
    /** Gone once this far below the camera, blocks; or, whatever happens, after {@value #MAX_AGE_TICKS} ticks. */
    private static final double GONE_BELOW = 250.0;
    private static final int MAX_AGE_TICKS = 400;
    /** Head size, blocks: each comet rolls its own, small ones far more often than big ones. */
    private static final double SIZE_MIN = 1.0, SIZE_MAX = 6.0;
    /** Streak length and width at a 3-block head; they scale with the head. */
    private static final double TAIL_LENGTH = 30.0;
    /** The streak's width where it leaves the head, blocks; it tapers to a point. */
    private static final double TAIL_WIDTH = 0.6;
    private static final BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();

    /** {@code start} is relative to the camera, which carries the comet with it. */
    private record Comet(int id, Vec3 start, Vec3 velocity, float size, int age) {
        Vec3 at(double ticks) {
            return start.add(velocity.scale(ticks));
        }

        Comet older() {
            return new Comet(id, start, velocity, size, age + 1);
        }

        boolean gone() {
            return age >= MAX_AGE_TICKS || at(age).y < -GONE_BELOW;
        }
    }

    private static List<Comet> live = List.of();

    private DistantComets() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        List<Comet> next = new ArrayList<>(live.size() + 1);
        for (Comet c : live) {
            Comet older = c.older();
            if (!older.gone()) next.add(older);
        }
        double camX = mc.gameRenderer.getMainCamera().getPosition().x;
        if (next.size() < MAX_LIVE && level.random.nextDouble() < chanceAt(camX)) {
            next.add(spawn(level.random));
        }
        if (!next.isEmpty() || !live.isEmpty()) live = List.copyOf(next);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        live = List.of();
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || live.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        // Past the terrain fog's edge on purpose: these are seen against the far void sky.
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        // Streaks then heads, each flushed on its own: drawing a block can end the shared streak buffer mid-way.
        VertexConsumer streak = buffer.getBuffer(RenderType.lightning());
        for (Comet c : live) {
            double ticks = c.age() + partial;
            tail(streak, ps.last().pose(), c.at(ticks), c.velocity().normalize(), c.size() / 3.0);
        }
        buffer.endBatch(RenderType.lightning());
        for (Comet c : live) {
            double ticks = c.age() + partial;
            head(mc, ps, buffer, c, c.at(ticks), ticks);
        }
        buffer.endBatch(RenderType.solid());
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
    }

    /** Lap and band at the camera's X, as {@code SpheresCometEvents#chanceAt} reads them for real comets. */
    private static double chanceAt(double worldX) {
        if (!ClientVoidBand.startsWithTrain()) return 0.0;
        int x = (int) Math.floor(worldX);
        WorldGenCycle cycle = WorldGenCycle.fromConfig();
        long lap = cycle.cycleIndex(EndFrostbite.frostX(cycle, x));
        return SpheresComets.distantChancePerTick(lap, WindZones.endSkyAt(x), WindZones.spheresVoidAt(x));
    }

    private static Comet spawn(RandomSource random) {
        double around = random.nextDouble() * Math.PI * 2.0;
        double out = OUT_MIN + random.nextDouble() * OUT_SPAN;
        Vec3 start = new Vec3(Math.cos(around) * out, UP_MIN + random.nextDouble() * UP_SPAN,
            Math.sin(around) * out);
        double heading = random.nextDouble() * Math.PI * 2.0;
        double drift = DRIFT_MIN + random.nextDouble() * DRIFT_SPAN;
        Vec3 velocity = new Vec3(Math.cos(heading) * drift, -(FALL_MIN + random.nextDouble() * FALL_SPAN),
            Math.sin(heading) * drift);
        // Squared, so most are small and only a few are big.
        float size = (float) (SIZE_MIN + Math.pow(random.nextDouble(), 2.0) * (SIZE_MAX - SIZE_MIN));
        return new Comet(random.nextInt(), start, velocity, size, 0);
    }

    private static void head(Minecraft mc, PoseStack ps, MultiBufferSource buffer, Comet c, Vec3 at, double ticks) {
        ps.pushPose();
        ps.translate(at.x, at.y, at.z);
        ps.mulPose(new Quaternionf().fromAxisAngleRad(SpheresComets.spinAxis(c.id()),
            SpheresComets.spinAngle(c.id(), ticks)));
        ps.scale(c.size(), c.size(), c.size());
        ps.translate(-0.5, -0.5, -0.5);
        mc.getBlockRenderer().renderSingleBlock(MAGMA, ps, buffer, LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());
        ps.popPose();
    }

    /**
     * One thin camera-facing streak from the head back along the flight line, tapering to a point and glowing
     * yellow to clear; both windings, so it shows whichever way it faces.
     */
    private static void tail(VertexConsumer out, Matrix4f pose, Vec3 head, Vec3 dir, double scale) {
        Vec3 side = dir.cross(head).normalize().scale(TAIL_WIDTH * 0.5 * scale);
        Vec3 end = head.subtract(dir.scale(TAIL_LENGTH * scale));
        Vec3 a = head.add(side), b = head.subtract(side);
        int alpha = 200;
        vertex(out, pose, a, 255, 210, 90, alpha);
        vertex(out, pose, b, 255, 210, 90, alpha);
        vertex(out, pose, end, 255, 70, 0, 0);
        vertex(out, pose, end, 255, 70, 0, 0);
        vertex(out, pose, end, 255, 70, 0, 0);
        vertex(out, pose, end, 255, 70, 0, 0);
        vertex(out, pose, b, 255, 210, 90, alpha);
        vertex(out, pose, a, 255, 210, 90, alpha);
    }

    private static void vertex(VertexConsumer out, Matrix4f pose, Vec3 p, int r, int g, int b, int a) {
        out.addVertex(pose, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, a);
    }
}
