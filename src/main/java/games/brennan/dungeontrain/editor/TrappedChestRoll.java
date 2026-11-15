package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import javax.annotation.Nullable;

/**
 * A plain chest's {@link ContainerContentsPool#trappedChance()}: the percent chance a spawned copy is
 * placed as a trapped chest instead. The editor plot keeps the authored chest; only spawned copies
 * swap, and the loot rolls exactly as it would for the plain chest.
 *
 * <p>Both halves of a double chest must land on the same answer or they come apart into two single
 * chests, so the roll drops the coordinate along the axis that joins them: the halves share every
 * other coordinate. The axis survives a carriage's mirror flip, where the side the partner sits on
 * does not.</p>
 */
public final class TrappedChestRoll {

    private static final long SALT_TRAPPED = 0x7EA9C4E5DB0C1A11L;
    private static final String NBT_ID = "id";
    private static final String TRAPPED_CHEST_ID = "minecraft:trapped_chest";

    private TrappedChestRoll() {}

    /** True for the one block the chance applies to: a plain vanilla chest. */
    public static boolean isTrappable(BlockState state) {
        return state.is(Blocks.CHEST);
    }

    /** Whether the chest at {@code localPos} spawns trapped. Deterministic per seed and carriage. */
    public static boolean rolls(ContainerContentsPool pool, BlockState state, BlockPos localPos,
                                long worldSeed, int carriageIndex) {
        if (pool == null || !isTrappable(state)) return false;
        int chance = pool.trappedChance();
        if (chance <= 0) return false;
        if (chance >= ContainerContentsPool.MAX_TRAPPED_CHANCE) return true;
        long mixed = ContainerContentsRoller.mix(rollKey(state, localPos), worldSeed, carriageIndex, 0, SALT_TRAPPED);
        return Math.floorMod(mixed, ContainerContentsPool.MAX_TRAPPED_CHANCE) < chance;
    }

    /** The cell the roll is keyed on: {@code localPos}, with a double chest's joining axis zeroed. */
    static BlockPos rollKey(BlockState state, BlockPos localPos) {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return localPos;
        }
        Direction.Axis axis = ChestBlock.getConnectedDirection(state).getAxis();
        return switch (axis) {
            case X -> new BlockPos(0, localPos.getY(), localPos.getZ());
            case Z -> new BlockPos(localPos.getX(), localPos.getY(), 0);
            case Y -> localPos;
        };
    }

    /** {@code state} as a trapped chest, keeping facing, chest type and waterlogging. */
    public static BlockState trapped(BlockState state) {
        return Blocks.TRAPPED_CHEST.withPropertiesOf(state);
    }

    /** {@code nbt} retagged for the trapped chest's block entity type, or null when there is none. */
    @Nullable
    public static CompoundTag trappedNbt(@Nullable CompoundTag nbt) {
        if (nbt == null || !nbt.contains(NBT_ID)) return nbt;
        CompoundTag out = nbt.copy();
        out.putString(NBT_ID, TRAPPED_CHEST_ID);
        return out;
    }

    /**
     * Whether a chest half about to be placed at {@code worldPos} must be trapped because its partner
     * half is already standing there as a trapped chest. Covers a double chest whose two halves have
     * different chances, or only one half authored.
     */
    public static boolean partnerTrapped(LevelReader level, BlockPos worldPos, BlockState state) {
        if (!isTrappable(state) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false;
        BlockState partner = level.getBlockState(worldPos.relative(ChestBlock.getConnectedDirection(state)));
        return partner.is(Blocks.TRAPPED_CHEST) && joined(state, partner);
    }

    /**
     * For a chest already in the world whose loot was just rolled from {@code pool}: swap it for a
     * trapped chest when the roll says so, and its partner half with it.
     */
    public static void swapPlaced(LevelAccessor level, BlockPos worldPos, BlockPos localPos,
                                  ContainerContentsPool pool, long worldSeed, int carriageIndex) {
        BlockState state = level.getBlockState(worldPos);
        if (!isTrappable(state)) return;
        if (!rolls(pool, state, localPos, worldSeed, carriageIndex) && !partnerTrapped(level, worldPos, state)) return;
        swapInWorld(level, worldPos, state);
        swapPartner(level, worldPos, trapped(state));
    }

    /** After a trapped half lands at {@code worldPos}: swap a plain partner half already placed. */
    public static void swapPartner(LevelAccessor level, BlockPos worldPos, BlockState trappedState) {
        if (!trappedState.is(Blocks.TRAPPED_CHEST) || trappedState.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        BlockPos partnerPos = worldPos.relative(ChestBlock.getConnectedDirection(trappedState));
        BlockState partner = level.getBlockState(partnerPos);
        if (partner.is(Blocks.CHEST) && joined(trappedState, partner)) swapInWorld(level, partnerPos, partner);
    }

    /** True when {@code other} is the half {@code half} joins to: same facing, opposite side. */
    private static boolean joined(BlockState half, BlockState other) {
        if (!other.hasProperty(ChestBlock.TYPE) || !other.hasProperty(ChestBlock.FACING)) return false;
        return other.getValue(ChestBlock.FACING) == half.getValue(ChestBlock.FACING)
            && other.getValue(ChestBlock.TYPE) == half.getValue(ChestBlock.TYPE).getOpposite();
    }

    /**
     * Replace the plain chest at {@code pos} with a trapped chest, carrying its block entity across.
     * The two chests have different block entity types, so the old one goes with the block; it is
     * emptied first so breaking it can't spill its loot on the floor.
     */
    private static void swapInWorld(LevelAccessor level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        CompoundTag contents = be == null ? null : be.saveCustomOnly(level.registryAccess());
        if (be instanceof Container container) container.clearContent();
        level.setBlock(pos, trapped(state), Block.UPDATE_CLIENTS);
        if (contents == null) return;
        BlockEntity swapped = level.getBlockEntity(pos);
        if (swapped == null) return;
        swapped.loadCustomOnly(contents, level.registryAccess());
        swapped.setChanged();
    }
}
