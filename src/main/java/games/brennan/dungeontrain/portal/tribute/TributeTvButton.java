package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The button under a tribute room's TV: pressing it is Next Screen on the picture
 * ({@code TributeTvClientEvents}), as a click on the TV would be.
 *
 * <p>Stamped each time a room stands rather than saved in the template, so every player's saved room
 * gets one as well as the bare template. It sits on the front of the block under the middle of the TV's
 * bottom row (the TV stand), facing the way the TV does, and only where that cell is free.</p>
 */
public final class TributeTvButton {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TributeTvButton() {}

    /** Whether {@code state} is the tribute TV's button block. */
    public static boolean isButton(BlockState state) {
        return state != null && state.is(Blocks.POLISHED_BLACKSTONE_BUTTON);
    }

    /** The cell the button stands in for a TV filling {@code screen} and facing {@code facing}; null for a vertical facing. */
    @Nullable
    public static BlockPos buttonCell(BoundingBox screen, Direction facing) {
        if (facing.getAxis().isVertical()) return null;
        boolean alongZ = facing.getAxis() == Direction.Axis.Z;
        // The front layer of the TV in the way it faces, the middle of its bottom row across.
        int front = alongZ ? (facing.getStepZ() > 0 ? screen.maxZ() : screen.minZ())
                           : (facing.getStepX() > 0 ? screen.maxX() : screen.minX());
        int middle = alongZ ? Math.floorDiv(screen.minX() + screen.maxX(), 2) : Math.floorDiv(screen.minZ() + screen.maxZ(), 2);
        BlockPos stand = alongZ ? new BlockPos(middle, screen.minY() - 1, front) : new BlockPos(front, screen.minY() - 1, middle);
        return stand.relative(facing);
    }

    /** A block of the TV the button at {@code button}, facing {@code facing}, sits under: back onto the stand, then up. */
    public static BlockPos tvAbove(BlockPos button, Direction facing) {
        return button.relative(facing.getOpposite()).above();
    }

    /**
     * Put the button under the TV in {@code room}. Leaves alone a room with no TV, no stand under it, or
     * something already in front of the stand; a button already there stays as it is.
     */
    public static void place(ServerLevel level, BoundingBox room) {
        List<BlockPos> screen = new ArrayList<>();
        Direction facing = TributeRoomVisitService.screenBlocks(level, room, screen);
        if (screen.isEmpty()) return;
        BlockPos cell = buttonCell(TributeRoomVisitService.boxOf(screen), facing);
        if (cell == null) return;
        BlockPos stand = cell.relative(facing.getOpposite());
        BlockState there = level.getBlockState(cell);
        if (isButton(there)) return;
        if (level.getBlockState(stand).isAir() || !there.isAir()) {
            LOGGER.debug("[DungeonTrain] tribute room: no TV button at {} (stand {}, cell holds {})", cell, level.getBlockState(stand), there);
            return;
        }
        BlockState button = Blocks.POLISHED_BLACKSTONE_BUTTON.defaultBlockState()
            .setValue(ButtonBlock.FACE, AttachFace.WALL)
            .setValue(ButtonBlock.FACING, facing);
        // No neighbour updates: the stand is a stair whose front is not a full face, and an update
        // would have the button check its support and drop.
        level.setBlock(cell, button, Block.UPDATE_CLIENTS);
    }
}
