package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderRoomGhosts;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what an open portal room's mode does at its walls, as translucent ghosts.
 *
 * <p>The builder stamps a room and nothing else, so a Bedrock room and a Repeating room look
 * identical while being the two most different things in the list. This draws the boundary each mode
 * implies: a bedrock skin, a plain of floor and ceiling, or the room repeating away into the
 * distance — see {@link BuilderRoomGhosts} for which is which.</p>
 *
 * <p><b>Drawn, never stamped.</b> The ghosts are quads; no block is placed. That is what keeps them
 * out of the saved template, out of the dirty check, and out of reach of the block protection —
 * stamping them and forbidding editing would have had all three to get right, and a bug in any one
 * writes scenery into somebody's room.</p>
 *
 * <p>Deliberately the same shape as {@link OutOfBoundsWashRenderer} beside it — a cadenced sweep
 * caching exposed faces, a flat-quad pass after translucent blocks — and for the same reason: real
 * transparency would mean forcing blocks into the translucent chunk layer from a meshing hook, and
 * Sodium replaces that meshing, so the effect would silently vanish for anyone running it.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderRoomGhostRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final RenderType GHOST_QUAD = MenuRenderStates.translucentQuad(
            DungeonTrain.MOD_ID + ":builder_room_ghost");

    private static final int RESCAN_INTERVAL_TICKS = 10;
    /** Hard ceiling on the copied faces, matching the wash's, so a big room can't stall a frame. */
    private static final int MAX_FACES = 16_384;

    /** Pulls the quad just clear of the neighbouring face so the two don't z-fight. */
    private static final double EXPAND = 0.002;

    private static final float R = 0.72F;
    private static final float G = 0.84F;
    private static final float B = 1.00F;
    /**
     * 75% at the room's own boundary — the Bedrock skin and the first ring of copies.
     *
     * <p>Nearly solid on purpose. These ghosts are not a hint that something is there; they are the
     * answer to "what does this room do at its walls", and at the faint tint the wash uses the
     * difference between a sealed room and a repeating one was a shimmer you had to look for.
     * Distance is what carries "this isn't real" instead — {@link BuilderRoomGhosts#fadeFor} takes
     * it down from here to nothing at the outer ring.</p>
     */
    private static final float A = 0.75F;

    /** Faces of the room in <b>local</b> coordinates, so one sweep serves every tile. */
    private static volatile List<Face> faces = List.of();
    private static volatile BlockPos origin = BlockPos.ZERO;
    private static volatile Vec3i roomSize = Vec3i.ZERO;
    private static volatile BuilderRoomGhosts.Ghosts ghosts =
            new BuilderRoomGhosts.Ghosts(List.of(), false, false, 0);

    private static int tickCounter = 0;
    /** Last size the face cap was reported at, so the warning is logged once rather than per sweep. */
    private static volatile Vec3i loggedCapFor = null;

    private record Face(BlockPos local, Direction dir) {}

    private BuilderRoomGhostRenderer() {}

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
        ghosts = new BuilderRoomGhosts.Ghosts(List.of(), false, false, 0);
        loggedCapFor = null;
    }

    /**
     * Work out what the mode wants drawn, then cache the room's exposed faces.
     *
     * <p>The mode is read from the room's own {@code weights.json} tag through
     * {@link PortalRoomMode}. That is a server-side store and this is the client — but a builder
     * world is single-player by construction, so the same process holds both, exactly as
     * {@code EditorTemplateLists} already relies on for the Open grid.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        if (volumes.isEmpty() || mc.level == null
                || !BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(BuilderBoundsState.subTypeId())) {
            clear();
            return;
        }

        String name = BuilderBoundsState.buildName();
        if (name == null || name.isEmpty()) {
            clear();
            return;
        }

        BoundingBox box = volumes.get(0);
        Vec3i size = new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan());
        PortalRoomMode mode = modeOf(name);
        BuilderRoomGhosts.Ghosts wanted = BuilderRoomGhosts.of(mode, size);
        if (wanted.isEmpty()) {
            clear();
            return;
        }
        // Endless Open repeats the floor and ceiling planes and nothing between them — that is what
        // "the walls are open" means, and a ghost that copied the walls would be drawing the one
        // thing this mode promises isn't there.
        boolean planesOnly = mode.tiles() && !mode.tilesWholeRoom();

        Level level = mc.level;
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        List<Face> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        boolean capped = false;

        outer:
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    pos.set(x, y, z);
                    if (level.getBlockState(pos).isAir()) continue;
                    for (Direction dir : Direction.values()) {
                        neighbour.set(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                        // Only faces onto air: the inside of a solid mass is invisible anyway, and
                        // skipping it is what keeps the quad count down on a furnished room.
                        if (!level.getBlockState(neighbour).isAir()) continue;
                        int localY = y - min.getY();
                        if (planesOnly && localY != 0 && localY != size.getY() - 1) continue;
                        found.add(new Face(
                                new BlockPos(x - min.getX(), localY, z - min.getZ()), dir));
                        if (found.size() >= MAX_FACES) {
                            capped = true;
                            break outer;
                        }
                    }
                }
            }
        }

        // Never silently: a truncated sweep draws a room with holes in it, and a builder would read
        // that as the room having holes rather than the ghost giving up.
        if (capped && !size.equals(loggedCapFor)) {
            LOGGER.info("[DungeonTrain] Builder room ghosts: '{}' ({}x{}x{}) hit the {}-face cap — "
                            + "the near copies are drawn partially",
                    name, size.getX(), size.getY(), size.getZ(), MAX_FACES);
            loggedCapFor = size;
        } else if (!capped) {
            loggedCapFor = null;
        }

        origin = min;
        roomSize = size;
        faces = found;
        ghosts = wanted;
    }

    /** The authored mode for {@code name}, defaulting the way every other reader of the tag does. */
    private static PortalRoomMode modeOf(String name) {
        try {
            return games.brennan.dungeontrain.portal.PortalRoomSettings.of(name).mode();
        } catch (RuntimeException e) {
            // The registry is a server-side store; if this ever runs where there isn't one, a
            // missing ghost is a far better outcome than a crashed render thread.
            return PortalRoomMode.DEFAULT;
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        BuilderRoomGhosts.Ghosts slots = ghosts;
        List<Face> snapshot = faces;
        Vec3i size = roomSize;
        if (slots.isEmpty() || size.getX() <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(GHOST_QUAD);
        BlockPos base = origin;

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // Bedrock Lock: a one-block skin hugging the room, drawn as the box one block out.
        if (slots.shell()) {
            drawBox(ps, vc, base.offset(-1, -1, -1),
                    new Vec3i(size.getX() + 2, size.getY() + 2, size.getZ() + 2), A);
        }

        for (BuilderRoomGhosts.Tile tile : slots.tiles()) {
            int ox = tile.tileX() * size.getX();
            int oz = tile.tileZ() * size.getZ();
            float alpha = A * BuilderRoomGhosts.fadeFor(tile.ring(), slots.maxRing());
            if (alpha <= 0.001f) continue;

            if (tile.detail() == BuilderRoomGhosts.Detail.FULL) {
                for (Face face : snapshot) {
                    drawFace(ps, vc,
                            base.getX() + ox + face.local().getX(),
                            base.getY() + face.local().getY(),
                            base.getZ() + oz + face.local().getZ(),
                            face.dir(), alpha);
                }
            } else if (slots.planesOnly()) {
                // The distant silhouette of an open plain is its floor and roof, not a box.
                drawBox(ps, vc, base.offset(ox, 0, oz), new Vec3i(size.getX(), 1, size.getZ()), alpha);
                drawBox(ps, vc, base.offset(ox, size.getY() - 1, oz),
                        new Vec3i(size.getX(), 1, size.getZ()), alpha);
            } else {
                drawBox(ps, vc, base.offset(ox, 0, oz), size, alpha);
            }
        }

        ps.popPose();
        buffer.endBatch(GHOST_QUAD);
    }

    /** The six outer faces of a box at {@code min} spanning {@code size}. */
    private static void drawBox(PoseStack ps, VertexConsumer vc, BlockPos min, Vec3i size, float a) {
        double x0 = min.getX() - EXPAND;
        double y0 = min.getY() - EXPAND;
        double z0 = min.getZ() - EXPAND;
        double x1 = min.getX() + size.getX() + EXPAND;
        double y1 = min.getY() + size.getY() + EXPAND;
        double z1 = min.getZ() + size.getZ() + EXPAND;

        quad(ps, vc, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);   // down
        quad(ps, vc, a, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);   // up
        quad(ps, vc, a, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);   // north
        quad(ps, vc, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);   // south
        quad(ps, vc, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);   // west
        quad(ps, vc, a, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);   // east
    }

    /** One outset unit square on the given side of a block. */
    private static void drawFace(PoseStack ps, VertexConsumer vc, int bx, int by, int bz,
                                 Direction dir, float a) {
        double x0 = bx - EXPAND;
        double y0 = by - EXPAND;
        double z0 = bz - EXPAND;
        double x1 = bx + 1.0 + EXPAND;
        double y1 = by + 1.0 + EXPAND;
        double z1 = bz + 1.0 + EXPAND;

        switch (dir) {
            case DOWN -> quad(ps, vc, a, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            case UP -> quad(ps, vc, a, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case NORTH -> quad(ps, vc, a, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> quad(ps, vc, a, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> quad(ps, vc, a, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> quad(ps, vc, a, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
        }
    }

    private static void quad(PoseStack ps, VertexConsumer vc, float a,
                             double ax, double ay, double az, double bx, double by, double bz,
                             double cx, double cy, double cz, double dx, double dy, double dz) {
        org.joml.Matrix4f m = ps.last().pose();
        vc.addVertex(m, (float) ax, (float) ay, (float) az).setColor(R, G, B, a);
        vc.addVertex(m, (float) bx, (float) by, (float) bz).setColor(R, G, B, a);
        vc.addVertex(m, (float) cx, (float) cy, (float) cz).setColor(R, G, B, a);
        vc.addVertex(m, (float) dx, (float) dy, (float) dz).setColor(R, G, B, a);
    }
}
