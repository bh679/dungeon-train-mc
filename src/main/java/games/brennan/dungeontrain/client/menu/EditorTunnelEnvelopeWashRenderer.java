package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.EditorTunnelEnvelopePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
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
 * Washes tunnel-template blocks that stand where the train will run in translucent red, and traces
 * that zone with a faint red outline so an author can see it before placing anything.
 *
 * <p>The boxes come from {@link EditorTunnelEnvelopePacket}; which blocks sit in them is read from
 * the client's own chunks on a {@value #RESCAN_INTERVAL_TICKS}-tick cadence, so a placed or broken
 * block updates within half a second with no server traffic. Same tint-over-a-solid-block recipe as
 * {@link games.brennan.dungeontrain.client.builder.OutOfBoundsWashRenderer}, for the same reason: it
 * works under any chunk renderer. Only air-exposed faces are drawn.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class EditorTunnelEnvelopeWashRenderer {

    private static final RenderType WASH_QUAD = MenuRenderStates.translucentQuad(
            DungeonTrain.MOD_ID + ":tunnel_envelope_wash");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** Boxes further than this from the player are neither scanned nor outlined. */
    private static final double MAX_DISTANCE = 64.0;
    /** Hard ceiling so a pathological template can't stall a frame. */
    private static final int MAX_FACES = 16_384;

    private static final double EXPAND = 0.002;

    private static final float R = 1.0F;
    private static final float G = 0.15F;
    private static final float B = 0.15F;
    /** Wash opacity — slightly stronger than the builder's, since here every red block is a bug. */
    private static final float WASH_A = 0.35F;
    /** Zone outline opacity — a hint of where the train runs, not a wall. */
    private static final float LINE_A = 0.35F;

    private record Face(BlockPos pos, Direction dir) {}

    /** Envelope boxes from the server, replaced wholesale. */
    private static volatile List<BoundingBox> boxes = List.of();
    /** Cached exposed faces, replaced wholesale so a render pass never sees a partial sweep. */
    private static volatile List<Face> faces = List.of();
    private static int tickCounter = 0;

    private EditorTunnelEnvelopeWashRenderer() {}

    public static void applySnapshot(EditorTunnelEnvelopePacket packet) {
        boxes = packet.boxes();
        if (boxes.isEmpty()) faces = List.of();
        else rescan();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        boxes = List.of();
        faces = List.of();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        if (++tickCounter < RESCAN_INTERVAL_TICKS) return;
        tickCounter = 0;
        rescan();
    }

    private static void rescan() {
        List<BoundingBox> snapshot = boxes;
        Minecraft mc = Minecraft.getInstance();
        if (snapshot.isEmpty() || mc.level == null || mc.player == null) {
            faces = List.of();
            return;
        }
        Level level = mc.level;
        Vec3 eye = mc.player.position();
        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        for (BoundingBox box : snapshot) {
            if (!isNear(box, eye)) continue;
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (level.getBlockState(pos).isAir()) continue;
                        for (Direction dir : Direction.values()) {
                            neighbour.setWithOffset(pos, dir);
                            if (!level.getBlockState(neighbour).isAir()) continue;
                            found.add(new Face(pos.immutable(), dir));
                            if (found.size() >= MAX_FACES) {
                                faces = found;
                                return;
                            }
                        }
                    }
                }
            }
        }
        faces = found;
    }

    private static boolean isNear(BoundingBox box, Vec3 p) {
        double dx = Math.max(0, Math.max(box.minX() - p.x, p.x - (box.maxX() + 1)));
        double dy = Math.max(0, Math.max(box.minY() - p.y, p.y - (box.maxY() + 1)));
        double dz = Math.max(0, Math.max(box.minZ() - p.z, p.z - (box.maxZ() + 1)));
        return dx * dx + dy * dy + dz * dz <= MAX_DISTANCE * MAX_DISTANCE;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<BoundingBox> boxSnapshot = boxes;
        if (boxSnapshot.isEmpty()) return;
        List<Face> faceSnapshot = faces;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        if (!faceSnapshot.isEmpty()) {
            VertexConsumer vc = buffer.getBuffer(WASH_QUAD);
            for (Face face : faceSnapshot) {
                drawFace(ps, vc, face.pos(), face.dir());
            }
            buffer.endBatch(WASH_QUAD);
        }

        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        for (BoundingBox box : boxSnapshot) {
            if (!isNear(box, cam)) continue;
            LevelRenderer.renderLineBox(ps, lines,
                new AABB(box.minX(), box.minY(), box.minZ(),
                    box.maxX() + 1.0, box.maxY() + 1.0, box.maxZ() + 1.0),
                R, G, B, LINE_A);
        }
        buffer.endBatch(RenderType.lines());

        ps.popPose();
    }

    /** One outset unit square on the given side of a block. */
    private static void drawFace(PoseStack ps, VertexConsumer vc, BlockPos pos, Direction dir) {
        double x0 = pos.getX() - EXPAND;
        double y0 = pos.getY() - EXPAND;
        double z0 = pos.getZ() - EXPAND;
        double x1 = pos.getX() + 1.0 + EXPAND;
        double y1 = pos.getY() + 1.0 + EXPAND;
        double z1 = pos.getZ() + 1.0 + EXPAND;

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
        vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(R, G, B, WASH_A);
        vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(R, G, B, WASH_A);
        vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(R, G, B, WASH_A);
        vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(R, G, B, WASH_A);
    }
}
