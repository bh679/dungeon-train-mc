package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The two bridges a {@link PortalRoomMode#CHUNK_PLANK_BRIDGE Plank bridge} chunk is reached by: one
 * block wide, of the stage's planks, from each corridor mouth across the door gap to the chunk's face.
 *
 * <h2>Where</h2>
 * <p>On each corridor's walkway line ({@code corridor.z + doorZ}) and on its floor row
 * ({@code corridor.y}, the row {@code PortalChunkDimension#openDoorway} cuts the doorway above), every
 * column of the {@link PortalStructure#doorGap} between the corridor and the room's box. The doors are
 * fitted to the sampled ground ({@link PortalChunkDoors}), so the bridge runs level from the corridor
 * floor straight onto the ground at the chunk's doorway. Its last plank stands in the skin's face
 * column, replacing the walk-through fluid guard there; a plank stops fluid as well as the guard did.</p>
 *
 * <h2>When</h2>
 * <p>Laid at stamp time, after the corridors and the skin. Every cell is inside
 * {@code PortalCarriageBuilder#footprintOf}, which is loaded before a stamp and swept by the erase, so
 * nothing is deferred and nothing needs erasing separately.</p>
 */
public final class PortalPlankBridge {

    /** The stage placeholder the bridge is built from — the stage's planks. */
    static final String PLANKS_PLACEHOLDER = "stage_planks";

    private PortalPlankBridge() {}

    /**
     * The cells of one bridge. Pure.
     *
     * @param faceX    the room box's end column on this side
     * @param floorY   the corridor's floor row
     * @param z        the corridor's walkway line
     * @param gap      the door gap
     * @param entry    true for the entry side (the bridge runs below {@code faceX}), false for the exit
     */
    static List<BlockPos> bridge(int faceX, int floorY, int z, int gap, boolean entry) {
        List<BlockPos> out = new ArrayList<>(Math.max(0, gap));
        for (int i = 1; i <= gap; i++) {
            out.add(new BlockPos(entry ? faceX - i : faceX + i, floorY, z));
        }
        return out;
    }

    /** Both bridges of {@code structure}, entry first; empty for a room that lays none. */
    static List<BlockPos> cells(PortalStructure structure, CarriageDims dims) {
        if (!structure.mode().laysPlankBridge() || structure.doorGap() <= 0) return List.of();
        PortalCarriageLayout layout = PortalCarriageBuilder.layoutFor(dims, structure.kind());
        int gap = structure.doorGap();

        BlockPos entryCorridor = structure.origin();
        BlockPos roomOrigin = structure.roomOrigin(dims, layout);
        List<BlockPos> out = new ArrayList<>(bridge(roomOrigin.getX(), entryCorridor.getY(),
            entryCorridor.getZ() + layout.doorZ(), gap, true));

        // The exit corridor faces the room on the tile it stands beside, which is the base room
        // unless the pair moved its exit.
        BlockPos exitCorridor = structure.exitOrigin(dims);
        BlockPos exitRoom = structure.exitShadow().roomOrigin(dims, layout);
        out.addAll(bridge(exitRoom.getX() + structure.roomSize().getX() - 1, exitCorridor.getY(),
            exitCorridor.getZ() + layout.doorZ(), gap, false));
        return out;
    }

    /** Lay {@code structure}'s bridges, in the planks of the stage its pair resolves through. */
    public static void lay(ServerLevel level, PortalStructure structure, CarriageDims dims, int pairKey) {
        List<BlockPos> cells = cells(structure, dims);
        if (cells.isEmpty()) return;
        BlockState planks = planksFor(PortalCarriageBuilder.stageIdFor(level, pairKey, dims));
        for (BlockPos pos : cells) {
            // No neighbour updates: the doorway above is open to the chunk's water.
            level.setBlock(pos, planks, Block.UPDATE_CLIENTS);
        }
    }

    /** The stage's planks for {@code stageId}; oak planks if the placeholder cannot be found. */
    static BlockState planksFor(String stageId) {
        BlockState placeholder = StagePlaceholderBlocks.defaultState(PLANKS_PLACEHOLDER);
        if (placeholder == null) return Blocks.OAK_PLANKS.defaultBlockState();
        BlockState resolved = StagePlaceholderBlocks.resolve(placeholder, stageId);
        return StagePlaceholderBlocks.isPlaceholder(resolved) ? Blocks.OAK_PLANKS.defaultBlockState() : resolved;
    }
}
