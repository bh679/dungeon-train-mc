package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderGhostSlots;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the rest of the train as translucent ghosts around the carriage being edited.
 *
 * <p>Opening a carriage room or a part parks a single carriage — one template is one carriage — but
 * a carriage on its own doesn't tell you how it reads in a run, which is most of the point of the
 * outside view. The empty slots either side are filled in at {@value #A} opacity so the whole train
 * is still legible while what you can actually edit stays unambiguous.</p>
 *
 * <p><b>Drawn, never stamped.</b> The ghosts are quads; no block is placed for them. That is what
 * keeps them out of the saved template, out of the dirty check, and out of reach of the block
 * protection rules — the alternative (stamp them and forbid editing) would have had all three of
 * those to get right, and any bug in any of them writes scenery into somebody's carriage.</p>
 *
 * <p>The geometry comes free: a ghost carriage is a copy of the one already standing in the world,
 * so the sweep reads the parked volume out of the client's own level and the render pass draws those
 * same faces at a slot offset. Nothing has to be sent from the server, and nothing has to be kept in
 * step with it.</p>
 *
 * <p>Deliberately the same shape as {@link OutOfBoundsWashRenderer} beside it — a cadenced sweep
 * caching exposed faces, a flat-quad pass after translucent blocks — and for the same reason: real
 * transparency would mean forcing blocks into the translucent chunk layer from a meshing hook, and
 * Sodium replaces that meshing entirely, so the effect would silently vanish for anyone running
 * it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderGhostTrainRenderer {

    private static final RenderType GHOST_QUAD = MenuRenderStates.translucentQuad(
            DungeonTrain.MOD_ID + ":builder_ghost_train");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** Hard ceiling so a pathological build can't stall a frame. Matches the wash's. */
    private static final int MAX_FACES = 16_384;

    /** Pulls the quad just clear of the neighbouring ghost face so the two don't z-fight. */
    private static final double EXPAND = 0.002;

    private static final float R = 0.80F;
    private static final float G = 0.86F;
    private static final float B = 1.00F;
    /** 10% — present enough to read the silhouette, faint enough never to be mistaken for a block. */
    private static final float A = 0.10F;

    /**
     * Faces of the parked carriage in <b>local</b> coordinates, so one sweep serves every slot.
     * Replaced wholesale, so a render pass never sees a partial sweep.
     */
    private static volatile List<Face> faces = List.of();
    /** World position the local faces are measured from, and the slot offsets to repeat them at. */
    private static volatile BlockPos origin = BlockPos.ZERO;
    private static volatile List<Integer> slotOffsets = List.of();

    private static int tickCounter = 0;

    private record Face(BlockPos local, Direction dir) {}

    private BuilderGhostTrainRenderer() {}

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        rescan();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private static void clear() {
        faces = List.of();
        slotOffsets = List.of();
    }

    /**
     * Work out which slots are empty, then cache the parked carriage's exposed faces.
     *
     * <p>Both halves of "which slots" are already on the client: {@code volumes.size()} is what is
     * parked, and the mode id says what a whole carriage would have parked. No new packet field, and
     * no third place for the two to disagree.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty() || mc.level == null || !ClientDisplayConfig.isBuilderGhostTrainEnabled()) {
            clear();
            return;
        }

        BoundingBox parked = volumes.get(0);
        int full = BuilderMode.fromId(BuilderBoundsState.modeId())
                .map(BuilderMode::carriageCount)
                .orElse(0);
        List<Integer> offsets = BuilderGhostSlots.offsets(volumes.size(), full,
                parked.maxX() - parked.minX() + 1);
        if (offsets.isEmpty()) {
            clear();
            return;
        }

        Level level = mc.level;
        BlockPos min = new BlockPos(parked.minX(), parked.minY(), parked.minZ());
        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        outer:
        for (int x = parked.minX(); x <= parked.maxX(); x++) {
            for (int y = parked.minY(); y <= parked.maxY(); y++) {
                for (int z = parked.minZ(); z <= parked.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    for (Direction dir : Direction.values()) {
                        neighbour.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                        // Only faces onto air: the inside of a solid mass is invisible anyway, and
                        // skipping it is what keeps the quad count down on a full carriage.
                        if (!level.getBlockState(neighbour).isAir()) continue;
                        found.add(new Face(new BlockPos(x - min.getX(), y - min.getY(), z - min.getZ()), dir));
                        if (found.size() >= MAX_FACES) break outer;
                    }
                }
            }
        }

        origin = min;
        slotOffsets = offsets;
        faces = found;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<Face> snapshot = faces;
        List<Integer> offsets = slotOffsets;
        if (snapshot.isEmpty() || offsets.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(GHOST_QUAD);
        BlockPos base = origin;

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (int offset : offsets) {
            for (Face face : snapshot) {
                drawFace(ps, vc,
                        base.getX() + offset + face.local().getX(),
                        base.getY() + face.local().getY(),
                        base.getZ() + face.local().getZ(),
                        face.dir());
            }
        }
        ps.popPose();
        buffer.endBatch(GHOST_QUAD);
    }

    /** One outset unit square on the given side of a block. */
    private static void drawFace(PoseStack ps, VertexConsumer vc, int bx, int by, int bz, Direction dir) {
        double x0 = bx - EXPAND;
        double y0 = by - EXPAND;
        double z0 = bz - EXPAND;
        double x1 = bx + 1.0 + EXPAND;
        double y1 = by + 1.0 + EXPAND;
        double z1 = bz + 1.0 + EXPAND;

        switch (dir) {
            case DOWN -> quad(ps, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            case UP -> quad(ps, vc, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case NORTH -> quad(ps, vc, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> quad(ps, vc, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> quad(ps, vc, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> quad(ps, vc, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        }
    }

    private static void quad(PoseStack ps, VertexConsumer vc,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        org.joml.Matrix4f m = ps.last().pose();
        vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(R, G, B, A);
        vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(R, G, B, A);
        vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(R, G, B, A);
        vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(R, G, B, A);
    }
}
