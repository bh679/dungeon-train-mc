package games.brennan.dungeontrain.client.builder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderOpenRequest;
import games.brennan.dungeontrain.builder.BuilderRoomGhosts;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws what an open portal room's mode does at its walls, as textured ghosts.
 *
 * <p>The builder stamps a room and nothing else, so a Bedrock room and a Repeating room look
 * identical while being the two most different things in the list. This draws the boundary each mode
 * implies: a bedrock skin, a plain of floor and ceiling, or the room repeating away into the
 * distance — see {@link BuilderRoomGhosts} for which is which.</p>
 *
 * <p><b>Textured, from each cell's own block model</b> — {@link BuilderGhostCells}, the same tool
 * {@code BuilderTrackGhostRenderer} draws the line with. A room ghosts as the room — its stone, its
 * lamps, its floor — because what you are judging is a real space repeating against the one you are
 * standing in, and flat silhouette quads read as an abstract shape hanging in the air.</p>
 *
 * <p>Two things this class brings to that tool. The culling lookup {@link #neighbourOf wraps around
 * the tile grid}, so the seam where two copies meet is culled as the interior it is. And the tiles
 * are ordered nearest-first per frame, which is what makes the shared batch's depth write land the
 * closest surface on each pixel — sorted by tile rather than by cell, a hundred-odd comparisons
 * instead of a hundred thousand.</p>
 *
 * <p><b>Drawn, never stamped.</b> No block is placed. That is what keeps the ghosts out of the saved
 * template, out of the dirty check, and out of reach of the block protection — stamping them and
 * forbidding editing would have had all three to get right, and a bug in any one writes scenery into
 * somebody's room.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BuilderRoomGhostRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RESCAN_INTERVAL_TICKS = 5;

    /**
     * Hard ceiling on the cells held for a frame.
     *
     * <p>A full window is 120 tiles and a default 11×7×13 room is a thousand cells, so drawing every
     * copy in full is six figures of block models. {@link BuilderRoomGhosts.Detail#SHELL} is what
     * keeps that down — see {@link #cellsFor} — and this is the backstop under it.</p>
     */
    private static final int MAX_CELLS = 40_000;

    /**
     * Opacity at the room's own boundary — the Bedrock skin and the first ring of copies.
     *
     * <p>Higher than the track ghosts' half, because these answer "what does this room do at its
     * walls" rather than sitting behind you as context. Distance carries "this isn't real" instead:
     * {@link BuilderRoomGhosts#fadeFor} takes it from here down to nothing at the outer ring.</p>
     */
    private static final float A = 0.75F;

    /** Room cells in <b>local</b> coordinates, so one sweep serves every tile. */
    private static volatile Map<BlockPos, BlockState> cells = Map.of();
    /** The Bedrock Lock skin, likewise local — a different shape, so it can't share the sweep. */
    private static volatile Map<BlockPos, BlockState> skin = Map.of();
    private static volatile BlockPos origin = BlockPos.ZERO;
    private static volatile Vec3i roomSize = Vec3i.ZERO;
    private static volatile BuilderRoomGhosts.Ghosts ghosts =
            new BuilderRoomGhosts.Ghosts(List.of(), false, false, 0);

    private static int tickCounter = 0;
    private static volatile Vec3i loggedCapFor = null;

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
        cells = Map.of();
        skin = Map.of();
        ghosts = new BuilderRoomGhosts.Ghosts(List.of(), false, false, 0);
        loggedCapFor = null;
    }

    /**
     * Work out what the mode wants drawn, then cache the room's cells.
     *
     * <p>On the tick sweep rather than in the render pass, the rule the track ghosts follow too: this
     * walks the level block by block, and the render pass only replays the result.</p>
     *
     * <p>The mode is read from the room's own {@code weights.json} tag. That is a server-side store
     * and this is the client — but a builder world is single-player by construction, so the same
     * process holds both, exactly as {@code EditorTemplateLists} already relies on for the Open
     * grid.</p>
     */
    private static void rescan() {
        Minecraft mc = Minecraft.getInstance();
        List<BoundingBox> volumes = BuilderBoundsState.volumes();
        // The same switch the carriage ghosts read. One toggle for "draw the builder's ghosts",
        // because from the player's side that is one thing — two settings for two renderers drawing
        // the same idea would be a distinction only the code cares about.
        if (volumes.isEmpty() || mc.level == null
                || !ClientDisplayConfig.isBuilderGhostTrainEnabled()
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

        Level level = mc.level;
        BlockPos min = new BlockPos(box.minX(), box.minY(), box.minZ());
        Map<BlockPos, BlockState> found = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        boolean capped = false;

        outer:
        for (int y = 0; y < size.getY(); y++) {
            // Endless Open repeats the floor and the ceiling and nothing between them — that is what
            // "the walls are open" means, and copying the walls would draw the one thing this mode
            // promises isn't there.
            if (wanted.planesOnly() && y != 0 && y != size.getY() - 1) {
                continue;
            }
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockState state = level.getBlockState(
                            probe.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    if (state.isAir()) {
                        continue;
                    }
                    found.put(new BlockPos(x, y, z), state);
                    if (found.size() >= MAX_CELLS) {
                        capped = true;
                        break outer;
                    }
                }
            }
        }

        // Never silently: a truncated sweep draws a room with holes in it, and a builder would read
        // that as the room having holes rather than the ghost giving up.
        if (capped && !size.equals(loggedCapFor)) {
            LOGGER.info("[DungeonTrain] Builder room ghosts: '{}' ({}x{}x{}) hit the {}-cell cap — "
                            + "the copies are drawn partially",
                    name, size.getX(), size.getY(), size.getZ(), MAX_CELLS);
            loggedCapFor = size;
        } else if (!capped) {
            loggedCapFor = null;
        }

        origin = min;
        roomSize = size;
        cells = found;
        skin = wanted.shell() ? bedrockSkin(size) : Map.of();
        ghosts = wanted;
    }

    /**
     * A one-block bedrock shell around the room — what Bedrock Lock actually writes outside the box.
     *
     * <p>Real bedrock rather than an outlined box, so the tile that says "Bedrock" and the thing
     * standing around the room are visibly the same claim.</p>
     */
    private static Map<BlockPos, BlockState> bedrockSkin(Vec3i size) {
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int x = -1; x <= size.getX(); x++) {
            for (int y = -1; y <= size.getY(); y++) {
                for (int z = -1; z <= size.getZ(); z++) {
                    boolean onShell = x == -1 || x == size.getX()
                            || y == -1 || y == size.getY()
                            || z == -1 || z == size.getZ();
                    if (onShell) {
                        out.put(new BlockPos(x, y, z), bedrock);
                    }
                }
            }
        }
        return out;
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
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        BuilderRoomGhosts.Ghosts slots = ghosts;
        Map<BlockPos, BlockState> room = cells;
        Map<BlockPos, BlockState> shell = skin;
        Vec3i size = roomSize;
        if (slots.isEmpty() || size.getX() <= 0 || (room.isEmpty() && shell.isEmpty())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }

        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(BuilderGhostCells.GHOST);
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();
        BlockPos base = origin;

        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // Bedrock Lock: the skin, at full strength — it is the room's own boundary, not a copy of it.
        if (!shell.isEmpty()) {
            drawCells(ps, vc, blocks, level, random, shell, shell, base, 0, 0, A, /*wrap*/ false, size);
        }

        // Nearest tile first: the batch writes depth, so whichever fragment lands first wins the
        // pixel. Sorted per frame because the ordering is the camera's, not the world's — and by
        // tile rather than by cell, which is a hundred-odd comparisons instead of a hundred thousand.
        List<BuilderRoomGhosts.Tile> ordered = new ArrayList<>(slots.tiles());
        ordered.sort(Comparator.comparingDouble(t -> tileDistanceSqr(t, base, size, cam)));

        for (BuilderRoomGhosts.Tile tile : ordered) {
            float alpha = A * BuilderRoomGhosts.fadeFor(tile.ring(), slots.maxRing());
            if (alpha <= 0.01f) {
                continue;
            }
            Map<BlockPos, BlockState> draw = cellsFor(room, tile);
            if (draw.isEmpty()) {
                continue;
            }
            drawCells(ps, vc, blocks, level, random, draw, room,
                    base, tile.tileX() * size.getX(), tile.tileZ() * size.getZ(),
                    alpha, /*wrap*/ true, size);
        }

        ps.popPose();
        buffer.endBatch(BuilderGhostCells.GHOST);
    }

    /**
     * Which cells a tile contributes.
     *
     * <p>The touching ring is the whole room, because that is what sells "this repeats". Further out
     * it is the floor course alone — a hundred-odd cells instead of a thousand, and at that distance
     * the ground running away from you is what reads anyway. Without this a full window would be six
     * figures of block models every frame.</p>
     */
    private static Map<BlockPos, BlockState> cellsFor(Map<BlockPos, BlockState> room,
                                                      BuilderRoomGhosts.Tile tile) {
        if (tile.detail() == BuilderRoomGhosts.Detail.FULL) {
            return room;
        }
        Map<BlockPos, BlockState> floor = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : room.entrySet()) {
            if (e.getKey().getY() == 0) {
                floor.put(e.getKey(), e.getValue());
            }
        }
        return floor;
    }

    /** Rough distance from the camera to a tile's centre, for the front-to-back ordering. */
    private static double tileDistanceSqr(BuilderRoomGhosts.Tile tile, BlockPos base, Vec3i size,
                                          Vec3 cam) {
        double cx = base.getX() + tile.tileX() * size.getX() + size.getX() / 2.0;
        double cy = base.getY() + size.getY() / 2.0;
        double cz = base.getZ() + tile.tileZ() * size.getZ() + size.getZ() / 2.0;
        return (cx - cam.x) * (cx - cam.x) + (cy - cam.y) * (cy - cam.y) + (cz - cam.z) * (cz - cam.z);
    }

    /**
     * One tile's worth of ghost blocks.
     *
     * @param neighbours the map the face-culling test consults — the whole room even when
     *                   {@code draw} is only its floor, so a distant course still hides its own
     *                   underside against the room above it
     * @param wrap       whether the culling lookup wraps around the tile grid. True for a copy, whose
     *                   outward faces are interior seams between two copies; false for the Bedrock
     *                   skin, which has no neighbour and must keep its outside
     */
    private static void drawCells(PoseStack ps, VertexConsumer vc, BlockRenderDispatcher blocks,
                                  Level level, RandomSource random,
                                  Map<BlockPos, BlockState> draw, Map<BlockPos, BlockState> neighbours,
                                  BlockPos base, int offsetX, int offsetZ, float alpha,
                                  boolean wrap, Vec3i size) {
        BuilderGhostCells.draw(ps, vc, blocks, level, random, draw, neighbours,
                next -> neighbourOf(next, wrap, size),
                new BlockPos(base.getX() + offsetX, base.getY(), base.getZ() + offsetZ), alpha);
    }

    /**
     * The cell next door, in the local frame.
     *
     * <p>Wrapped on X and Z for a tiled copy: the cell past the room's edge is the same cell at the
     * far edge of the next copy, so a face there is an interior seam, and drawing it would put a
     * bright wall through the middle of what should read as continuous space. Never wrapped on Y —
     * the grid is horizontal, so the floor's underside and the ceiling's top really are outside
     * faces.</p>
     */
    private static BlockPos neighbourOf(BlockPos next, boolean wrap, Vec3i size) {
        if (!wrap) {
            return next;
        }
        return new BlockPos(
                Math.floorMod(next.getX(), size.getX()),
                next.getY(),
                Math.floorMod(next.getZ(), size.getZ()));
    }

}
