package games.brennan.dungeontrain.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.perks.effects.hoarder.ChestSense;
import games.brennan.dungeontrain.perks.effects.hoarder.ChestSenseClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chest Sense's outlines and ring: a gold box around every container in the last pulse
 * ({@link ChestSenseClientState}), drawn without a depth test so it shows through walls, fading as
 * the glow ends. Containers on a carriage are plot-local and placed through their sub-level's
 * {@link ClientSubLevel#renderPose(float)}, the pose Sable draws the carriage with, so the boxes ride
 * a moving train exactly (as {@link CarriageGroupGapDebugRenderer} does).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class ChestSenseRenderer {

    private static final float R = 1.0F;
    private static final float G = 0.82F;
    private static final float B = 0.25F;
    /** Inset so the box sits just outside a full block without z-fighting its faces. */
    private static final double GROW = 0.02;

    /** Box corner indices for the 12 edges, corners numbered by bits x=1, y=2, z=4. */
    private static final int[][] EDGES = {
        {0, 1}, {2, 3}, {4, 5}, {6, 7},
        {0, 2}, {1, 3}, {4, 6}, {5, 7},
        {0, 4}, {1, 5}, {2, 6}, {3, 7},
    };

    /** Particles in the ring, spread evenly around the player. */
    private static final int RING_POINTS = 40;

    private ChestSenseRenderer() {}

    /**
     * The pulse's ring: each client tick for the first second after a pulse, a circle of particles at
     * the player's feet whose radius grows from 0 to the range ({@link ChestSenseClientState#ringRadius}).
     */
    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        float radius = ChestSenseClientState.ringRadius();
        if (radius <= 0F) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;
        Vec3 at = mc.player.position();
        for (int i = 0; i < RING_POINTS; i++) {
            double angle = Math.PI * 2.0 * i / RING_POINTS;
            mc.level.addParticle(net.minecraft.core.particles.ParticleTypes.WAX_ON,
                at.x + Math.cos(angle) * radius, at.y + 0.2, at.z + Math.sin(angle) * radius, 0.0, 0.0, 0.0);
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<ChestSense.Sensed> sensed = ChestSenseClientState.active();
        if (sensed.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;

        Map<UUID, ClientSubLevel> subs = new HashMap<>();
        ClientSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ClientSubLevel sl : container.getAllSubLevels()) subs.put(sl.getUniqueId(), sl);
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float alpha = ChestSenseClientState.strength();
        Vec3 cam = event.getCamera().getPosition();
        PoseStack ps = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(States.SEE_THROUGH_LINES);
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = ps.last().pose();
        Vector3d[] corners = new Vector3d[8];
        for (int i = 0; i < 8; i++) corners[i] = new Vector3d();

        for (ChestSense.Sensed s : sensed) {
            Pose3dc pose = null;
            if (s.subLevel() != null) {
                ClientSubLevel sub = subs.get(s.subLevel());
                if (sub == null) continue;
                pose = sub.renderPose(partialTick);
                if (pose == null) continue;
            }
            fillCorners(corners, s.pos(), pose);
            for (int[] e : EDGES) line(vc, matrix, corners[e[0]], corners[e[1]], alpha);
        }
        ps.popPose();
        buffer.endBatch(States.SEE_THROUGH_LINES);
    }

    private static void fillCorners(Vector3d[] out, BlockPos pos, Pose3dc pose) {
        for (int i = 0; i < 8; i++) {
            out[i].set(pos.getX() + ((i & 1) == 0 ? -GROW : 1 + GROW),
                pos.getY() + ((i & 2) == 0 ? -GROW : 1 + GROW),
                pos.getZ() + ((i & 4) == 0 ? -GROW : 1 + GROW));
            if (pose != null) pose.transformPosition(out[i], out[i]);
        }
    }

    private static void line(VertexConsumer vc, Matrix4f m, Vector3d a, Vector3d b, float alpha) {
        float nx = (float) (b.x - a.x);
        float ny = (float) (b.y - a.y);
        float nz = (float) (b.z - a.z);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        vc.addVertex(m, (float) a.x, (float) a.y, (float) a.z).setColor(R, G, B, alpha).setNormal(nx, ny, nz);
        vc.addVertex(m, (float) b.x, (float) b.y, (float) b.z).setColor(R, G, B, alpha).setNormal(nx, ny, nz);
    }

    /** Reaches {@link RenderStateShard}'s protected shards for a lines type with no depth test. */
    private static final class States extends RenderStateShard {

        static final RenderType SEE_THROUGH_LINES = RenderType.create(
            DungeonTrain.MOD_ID + ":chest_sense_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES, 1024, false, false,
            RenderType.CompositeState.builder()
                .setShaderState(RENDERTYPE_LINES_SHADER)
                .setLineState(new LineStateShard(java.util.OptionalDouble.of(2.0)))
                .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setWriteMaskState(COLOR_WRITE)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .createCompositeState(false));

        private States() {
            super("dungeontrain_chest_sense_states", () -> {}, () -> {});
        }
    }
}
