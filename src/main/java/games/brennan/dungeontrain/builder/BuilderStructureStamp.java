package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.structure.BuilderOpenTemplate;
import games.brennan.dungeontrain.builder.structure.BuilderStructure;
import games.brennan.dungeontrain.builder.structure.BuilderStructureCells;
import games.brennan.dungeontrain.builder.structure.BuilderStructureMode;
import games.brennan.dungeontrain.builder.structure.BuilderStructureNeeds;
import games.brennan.dungeontrain.builder.structure.BuilderStructureRefresh;
import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.ship.sable.WorldgenForceGuard;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands the structures around a build up as real blocks, or takes them back down.
 *
 * <p>The third consumer of {@link BuilderStructureNeeds}, beside the renderer and the pause-menu
 * control. It answers one question — <em>is this scenery in the world right now?</em> — and it
 * answers it for the whole declaration at once, so no mode can end up with scenery another mode put
 * there.</p>
 *
 * <h2>Idempotent, because the declaration is recomputed</h2>
 *
 * <p>Taking scenery down needs to know where it was, and the obvious way to know is to have recorded
 * it on the way up. <b>That is exactly what this does not do.</b> The mechanism this replaces kept a
 * captured copy, and every bug it had came from the copy rather than from the copying — a record
 * taken in one mode replayed in another, a second read finding what the first had taken. Here, the
 * positions to clear are re-derived from the same pure function that produced the positions to
 * write, so putting scenery up and taking it down are the same walk with a different block, and
 * calling either one twice costs a pass of reads.</p>
 *
 * <h2>Never the build</h2>
 *
 * <p>Every write is gated on {@link BuilderStructureNeeds#allows}, which refuses anything inside a
 * build volume and anything in the two platform courses the environment owns. The carriage shell is
 * the one exception and it is safe by construction: it is the exact complement of the box a carriage
 * room saves ({@link BuilderStructure#onSkin}), so clearing it cannot touch a single block somebody
 * placed in their room.</p>
 */
public final class BuilderStructureStamp {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Ceiling on blocks moved in one pass.
     *
     * <p>A repeating portal room tiles into a window of over a hundred copies, and a large room is a
     * few thousand cells each — solidifying all of it would be millions of writes. The cap is
     * announced rather than silent: a partly-stood-up scene read as "the scenery is broken" would be
     * a worse outcome than being told it was too big.</p>
     */
    private static final int MAX_CELLS = 200_000;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private BuilderStructureStamp() {}

    /**
     * Reconcile this world's structures with what its mode asks for.
     *
     * <p>Call after anything that changes what is open or what the control says — every New, Open,
     * switch and reopen, plus the control itself. Cheap when there is nothing to do.</p>
     */
    public static void apply(ServerLevel level) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        BuilderStructureMode mode =
                BuilderStructureMode.orDefault(data.builderStructureMode());
        List<BuilderStructure.Placement> placements =
                BuilderStructureNeeds.around(contextOf(level, data));
        if (placements.isEmpty()) {
            return;
        }

        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        BuilderStructureCells.Sources sources = sourcesOf(level, data, volumes);
        LongSet templateCells = templateCells(level, volumes);
        long t0 = System.nanoTime();
        int moved = 0;
        boolean capped = false;
        // Claimed cells, so an overlap is written once by the placement that owns it rather than
        // twice. Backwards for the same reason the client sweep goes backwards: the declaration's
        // order is "later wins", so the first claim reaching a cell is the right one. Getting this
        // wrong was never visibly broken — the last write still won, and clearing still cleared —
        // but it double-wrote every seam, and it made the block counts a lie: laying reported the
        // overlaps twice and clearing reported them once, so a symmetric pass looked lopsided.
        LongSet claimed = new LongOpenHashSet();

        for (int i = placements.size() - 1; i >= 0; i--) {
            BuilderStructure.Placement placement = placements.get(i);
            Map<BlockPos, BlockState> cells =
                    BuilderStructureCells.of(placement.kind(), sources);
            for (Map.Entry<BlockPos, BlockState> cell : cells.entrySet()) {
                if (moved >= MAX_CELLS) {
                    capped = true;
                    break;
                }
                BlockPos local = cell.getKey();
                BlockPos pos = placement.origin().offset(local);
                if (!BuilderStructureNeeds.allows(placement.kind(), pos, volumes, templateCells)) {
                    continue;
                }
                if (!claimed.add(pos.asLong())) {
                    continue;   // a later placement owns this cell
                }
                if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                    continue;
                }
                BlockState wanted = mode.stamps() ? cell.getValue() : AIR;
                if (level.getBlockState(pos).equals(wanted)) {
                    continue;   // already what it should be — most passes are almost all of this
                }
                WorldgenForceGuard.forceChunk(level, pos.getX() >> 4, pos.getZ() >> 4);
                level.setBlock(pos, wanted, 3);
                moved++;
            }
            if (capped) {
                break;
            }
        }

        if (capped) {
            LOGGER.warn("[DungeonTrain] Builder structures: hit the {}-block cap for '{}' — the scene "
                    + "is only partly {}", MAX_CELLS, mode.id(),
                    mode.stamps() ? "stood up" : "cleared");
        }
        if (moved > 0) {
            LOGGER.info("[DungeonTrain] Builder structures: '{}' — {} placement(s), {} block(s) {} in {} ms",
                    mode.id(), placements.size(), moved, mode.stamps() ? "laid" : "cleared",
                    (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /**
     * What this world is holding, in the terms the declaration asks about.
     *
     * <p>The server's half of the pair with {@code BuilderBoundsState.structures()}. Both fill in the
     * same record from their own side and call the same function, which is what makes them agree
     * without a packet keeping them in step.</p>
     */
    private static BuilderStructureNeeds.Context contextOf(ServerLevel level,
                                                           DungeonTrainWorldData data) {
        CarriageDims dims = data.dims();
        boolean room = BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType());
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        return new BuilderStructureNeeds.Context(
                BuilderMode.fromId(data.builderMode()).orElse(null),
                BuilderNewOptions.SubType.fromId(data.builderSubType()).orElse(null),
                TrackKind.fromId(data.builderTrackKind()),
                orEmpty(data.builderName()), dims,
                BuilderWorldSetup.parkedCarriages(data),
                room ? roomModeOf(data.builderName()) : null,
                room && !volumes.isEmpty() ? BuilderBounds.sizeOf(volumes.get(0)) : null,
                orEmpty(data.builderVariant()));
    }

    /** Everything the cell resolver needs, from this side. */
    private static BuilderStructureCells.Sources sourcesOf(ServerLevel level,
                                                           DungeonTrainWorldData data,
                                                           List<BoundingBox> volumes) {
        var blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        if (volumes.isEmpty()) {
            return new BuilderStructureCells.Sources(blocks, data.dims(), level.getSeed());
        }
        BoundingBox box = volumes.get(0);
        boolean room = BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(data.builderSubType());
        // A room's copies are copies of the room, and always have been — no control gates them.
        if (room) {
            return new BuilderStructureCells.Sources(blocks, data.dims(), level.getSeed(),
                    liveCells(level, box), BuilderBounds.sizeOf(box));
        }
        BuilderOpenTemplate open = BuilderStructureNeeds.openTemplateFor(contextOf(level, data));
        if (open == null) {
            return new BuilderStructureCells.Sources(blocks, data.dims(), level.getSeed());
        }
        boolean substitute = substitutes(data, volumes);
        return new BuilderStructureCells.Sources(blocks, data.dims(), level.getSeed(),
                substitute ? liveCells(level, box) : Map.of(),
                substitute ? BuilderBounds.sizeOf(box) : Vec3i.ZERO,
                open, substitute);
    }

    /**
     * Whether the open build's blocks stand in for the template they came from.
     *
     * <p>The geometry half of the rule, and the reason it is here rather than with the identity: the
     * live read is {@code volumes.get(0)}, so it can only speak for a template when there is exactly
     * one volume to be. A carriage group parks three and no single one of them is the build.</p>
     *
     * <p>{@code BuilderStructureScan} asks the same two things in the same order, and has to: the
     * client's solid-cell set is what the out-of-bounds wash reads, so a client resolving live
     * against a server that resolved from disk would paint real scenery red.</p>
     */
    private static boolean substitutes(DungeonTrainWorldData data, List<BoundingBox> volumes) {
        return volumes.size() == 1
                && BuilderStructureRefresh.orDefault(data.builderStructureRefresh()).substitutes();
    }

    /**
     * The build volume whose blocks the standing scenery is currently made of, or null.
     *
     * <p>Non-null only when all three of the things that have to be true are: the structures are
     * <em>solid</em>, so they occupy the world at all; the refresh setting says they follow the open
     * build; and the open build is a whole stored template that some of them are resolved from.
     * That is the exact condition under which re-stamping as the player edits changes anything, and
     * {@code BuilderStructureRestampTicker} asks it rather than re-deriving it — the two disagreeing
     * would mean either a ticker walking a volume for nothing, or scenery quietly not following.</p>
     */
    public static BoundingBox trackedOpenBuild(ServerLevel level) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return null;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        if (!BuilderStructureMode.orDefault(data.builderStructureMode()).stamps()) {
            return null;
        }
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);
        if (!substitutes(data, volumes)
                || BuilderStructureNeeds.openTemplateFor(contextOf(level, data)) == null) {
            return null;
        }
        return volumes.get(0);
    }

    /**
     * The open build's blocks as they are right now, local to its origin.
     *
     * <p>Only the room kinds read this, and only they should: a room's copies are copies of the room
     * you are building, so reading the last save instead would have the preview quietly disagreeing
     * with the work in front of you.</p>
     */
    private static Map<BlockPos, BlockState> liveCells(ServerLevel level, BoundingBox box) {
        Vec3i size = BuilderBounds.sizeOf(box);
        BlockPos min = BuilderBounds.originOf(box);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int x = 0; x < size.getX(); x++) {
                for (int z = 0; z < size.getZ(); z++) {
                    BlockState state = level.getBlockState(
                            probe.set(min.getX() + x, min.getY() + y, min.getZ() + z));
                    if (!state.isAir()) {
                        out.put(new BlockPos(x, y, z), state);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Every block the open template is holding, packed.
     *
     * <p>The build volumes say where a template <em>should</em> be; this says where it actually is.
     * The two differ where a stamp reaches past its plot — a tunnel is laid by {@code TunnelPlacer}
     * rather than cut to the box, so its walls can. Without this, a structure landing on one of
     * those blocks would replace it, which is the template losing to its own scenery.</p>
     */
    private static LongSet templateCells(ServerLevel level, List<BoundingBox> volumes) {
        if (volumes.isEmpty()) {
            return LongSets.EMPTY_SET;
        }
        LongSet out = new LongOpenHashSet();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BoundingBox box : volumes) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        if (!level.getBlockState(probe.set(x, y, z)).isAir()) {
                            out.add(probe.asLong());
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The authored mode for a room, defaulting the way every other reader of the tag does. */
    private static PortalRoomMode roomModeOf(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return PortalRoomSettings.of(name).mode();
        } catch (RuntimeException e) {
            return PortalRoomMode.DEFAULT;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
