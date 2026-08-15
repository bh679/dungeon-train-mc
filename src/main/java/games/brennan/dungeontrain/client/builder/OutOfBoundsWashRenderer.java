package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderBounds;
import games.brennan.dungeontrain.builder.BuilderMode;
import games.brennan.dungeontrain.builder.BuilderNewOptions;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderWorldLayout;
import games.brennan.dungeontrain.builder.BuilderGhostMode;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.CarriageDims;
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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Darkens blocks that sit outside the Train Builder's build volumes.
 *
 * <p>Only what's inside a carriage becomes the saved build, so anything placed beyond it — out on
 * the platform, above the roof, past the flatbed pads — is scaffolding you'd otherwise only
 * discover was missing at save time. The wash makes the boundary visible while you work.</p>
 *
 * <p><b>In {@link BuilderGhostMode#SOLID} it covers the scenery too</b> — the carriage skin, which is
 * inside the build volume, and the track, which is protected. Both are normally skipped and both
 * were ghosts a moment ago; standing them up as real blocks without the wash would leave the one
 * mode where you cannot tell the build from the things around it apart.</p>
 *
 * <p>It is a tint over a solid block, not real transparency. Making the block itself see-through
 * would mean forcing it into the translucent chunk layer from a chunk-meshing hook, and Sodium
 * replaces that meshing entirely — the effect would silently disappear for anyone running it. A
 * quad drawn after translucent blocks works under any renderer.</p>
 *
 * <p>Scanning every frame would be wasteful, so the block sweep runs on a
 * {@value #RESCAN_INTERVAL_TICKS}-tick cadence and the render pass just draws the cached faces.
 * Only faces exposed to air are emitted: the inside of a solid mass is invisible anyway, and
 * skipping it is what keeps the quad count down on a big scaffold.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class OutOfBoundsWashRenderer {

    /** Translucent, unlit, depth-tested colour quads — the same recipe the editor menus use. */
    private static final RenderType WASH_QUAD = MenuRenderStates.translucentQuad(
            DungeonTrain.MOD_ID + ":builder_out_of_bounds_wash");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** Horizontal reach of the sweep. Beyond this the wash isn't legible anyway. */
    private static final int SCAN_RADIUS_XZ = 24;
    /** Headroom above the carriage roof to sweep — enough to catch a tall scaffold. */
    private static final int SCAN_HEADROOM = 16;
    /** Hard ceiling so a pathological build can't stall a frame. */
    private static final int MAX_FACES = 16_384;

    private static final double EXPAND = 0.002;

    /**
     * Near-black rather than red.
     *
     * <p>Red was reading as an error, and most of what it lands on now isn't one: the flatbed pads
     * and the carriage group standing around a Solid build are out of bounds because they are
     * <em>scenery</em>, which is the normal state of things rather than a mistake. A dark fade says
     * the true thing — this is not part of what you are building — without saying anything is
     * wrong.</p>
     */
    private static final float R = 0.05F;
    private static final float G = 0.05F;
    private static final float B = 0.08F;
    /** 50% — the scenery sits clearly behind the build without going black. */
    private static final float A = 0.50F;

    /** Cached exposed faces, replaced wholesale so a render pass never sees a partial sweep. */
    private static volatile List<Face> faces = List.of();
    private static int tickCounter = 0;

    private record Face(BlockPos pos, Direction dir) {}

    private OutOfBoundsWashRenderer() {}

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
        faces = List.of();
        BuilderBoundsState.clear();
        // Template photos are read from this install's directory; the next world may be a different
        // one, and a kept entry would show one template's picture beside another's name.
        BuilderPhotoTextures.clear();
        // Same reasoning for the baked previews, plus these are GPU buffers worth handing back.
        BuilderTileMeshCache.clear();
    }

    private static void rescan() {
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        Minecraft mc = Minecraft.getInstance();
        if (volumes.isEmpty() || mc.level == null || mc.player == null) {
            faces = List.of();
            return;
        }

        Level level = mc.level;
        CarriageDims dims = CarriageDims.DEFAULT; // only used for the protection test's corridor width
        BuilderMode mode = BuilderMode.fromId(BuilderBoundsState.modeId()).orElse(null);
        // A track build unlocks the track rows, and the client knows it is in one the same way the
        // grid does: the sub type the server recorded names no carriage part.
        boolean trackBuildOpen = mode != null && !BuilderNewOptions.hasSubTypes(mode)
                && !BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(BuilderBoundsState.subTypeId());
        // In Solid the scenery is standing as real blocks, and all of it wants the wash — that is
        // the whole of what the wash now means. Two sets it otherwise skips: the carriage skin,
        // which is inside the build volume, and the track, which is protected. Both were ghosts a
        // moment ago and neither is part of what gets saved.
        boolean solid = BuilderGhostCellsState.mode() == BuilderGhostMode.SOLID;
        boolean shellIsScenery = solid && mode == BuilderMode.INSIDE_CARRIAGE
                && BuilderNewOptions.SubType.CARRIAGE_ROOM.id().equals(BuilderBoundsState.subTypeId());
        TrackGeometry track = TrackGeometry.from(dims, BuilderWorldLayout.TRAIN_Y);
        BlockPos centre = mc.player.blockPosition();

        int minX = centre.getX() - SCAN_RADIUS_XZ;
        int maxX = centre.getX() + SCAN_RADIUS_XZ;
        int minZ = centre.getZ() - SCAN_RADIUS_XZ;
        int maxZ = centre.getZ() + SCAN_RADIUS_XZ;
        // Y is bounded by the build band rather than the player: there is nothing above the
        // scaffold headroom and nothing below the build worth washing. Taken from the volumes rather
        // than from TRAIN_Y, which is where a carriage sits — a portal room stands two blocks lower,
        // so a fixed floor here would leave its bottom row out of the sweep.
        int minY = volumes.stream().mapToInt(BoundingBox::minY).min()
                .orElse(BuilderWorldLayout.TRAIN_Y) - 1;
        // The bed course sits a row below that, so a sweep floored on the build would wash the
        // rails and miss the bed they lie on.
        if (solid) {
            minY = Math.min(minY, track.bedY());
        }
        int maxY = volumes.stream().mapToInt(BoundingBox::maxY).max().orElse(BuilderWorldLayout.TRAIN_Y)
                + SCAN_HEADROOM;

        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();

        outer:
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    if (BuilderBounds.isInsideBuild(pos, volumes)
                            && !(shellIsScenery && onBuildSkin(pos, volumes))) {
                        continue;
                    }
                    // The platform and the track are scenery, not something the builder placed. In
                    // Solid the track is scenery you can see, so it gets the wash — the platform
                    // never does, being three hundred blocks of grass nobody is judging.
                    if (BuilderWorldLayout.isProtected(pos, dims, mode, trackBuildOpen)
                            && !(solid && onTrackRows(pos, track))) {
                        continue;
                    }

                    for (Direction dir : Direction.values()) {
                        neighbour.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                        if (!level.getBlockState(neighbour).isAir()) continue;
                        found.add(new Face(pos.immutable(), dir));
                        if (found.size() >= MAX_FACES) break outer;
                    }
                }
            }
        }
        faces = found;
    }

    /**
     * Whether {@code pos} is on the perimeter of the build it sits in — the carriage skin.
     *
     * <p>The client's own version of {@code BuilderGhostCells.onSkin}: the same set of cells, judged
     * from the volume the server already sent rather than from the dims. Only consulted for a
     * carriage room authored from inside, which is the one build whose skin is scenery.</p>
     */
    private static boolean onBuildSkin(BlockPos pos, List<BoundingBox> volumes) {
        for (BoundingBox box : volumes) {
            if (!box.isInside(pos)) {
                continue;
            }
            return pos.getX() == box.minX() || pos.getX() == box.maxX()
                    || pos.getY() == box.minY() || pos.getY() == box.maxY()
                    || pos.getZ() == box.minZ() || pos.getZ() == box.maxZ();
        }
        return false;
    }

    /** Whether {@code pos} is a bed or rail cell of the corridor, rather than the platform under it. */
    private static boolean onTrackRows(BlockPos pos, TrackGeometry track) {
        return (pos.getY() == track.bedY() || pos.getY() == track.railY())
                && pos.getZ() >= track.trackZMin() && pos.getZ() <= track.trackZMax();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        List<Face> snapshot = faces;
        if (snapshot.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(WASH_QUAD);

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Face face : snapshot) {
            drawFace(ps, vc, face.pos(), face.dir());
        }
        ps.popPose();
        buffer.endBatch(WASH_QUAD);
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
        vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(R, G, B, A);
        vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(R, G, B, A);
        vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(R, G, B, A);
        vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(R, G, B, A);
    }
}
