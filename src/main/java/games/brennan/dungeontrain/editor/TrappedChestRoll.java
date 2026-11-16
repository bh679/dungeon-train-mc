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
 * A plain chest row's {@link VariantState#trapped()} chance: the percent chance a spawned copy goes in
 * as a trapped chest instead. The chance is the row's, not the loot's, so a chest linked to a shared
 * loot prefab is trapped only where its row says so, and still rolls that prefab's loot.
 *
 * <p>Both halves of a double chest must land on the same answer or they come apart into two single
 * chests, so the roll drops the coordinate along the axis that joins them: the halves share every
 * other coordinate. The axis survives a carriage's mirror flip, where the side the partner sits on
 * does not. A half placed beside an already-trapped partner follows it ({@link #matchPartner}), and a
 * trapped half swaps a plain partner already standing ({@link #swapPartner}), so two halves with
 * different chances still stay joined.</p>
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

    /** {@code current + delta}, wrapping past either end of 0..{@link VariantState#MAX_TRAPPED}. */
    public static int wrapChance(int current, int delta) {
        int next = current + delta;
        if (next > VariantState.MAX_TRAPPED) return delta > 0 && current >= VariantState.MAX_TRAPPED ? 0 : VariantState.MAX_TRAPPED;
        if (next < 0) return delta < 0 && current <= 0 ? VariantState.MAX_TRAPPED : 0;
        return next;
    }

    /** Whether the chest at {@code localPos} spawns trapped. Deterministic per seed and carriage. */
    public static boolean rolls(int chance, BlockState state, BlockPos localPos, long worldSeed, int carriageIndex) {
        if (chance <= 0 || !isTrappable(state)) return false;
        if (chance >= VariantState.MAX_TRAPPED) return true;
        long mixed = ContainerContentsRoller.mix(rollKey(state, localPos), worldSeed, carriageIndex, 0, SALT_TRAPPED);
        return Math.floorMod(mixed, VariantState.MAX_TRAPPED) < chance;
    }

    /** {@code state} as a trapped chest when the row's chance rolls, else {@code state} unchanged. */
    public static BlockState apply(BlockState state, int chance, BlockPos localPos, long worldSeed, int carriageIndex) {
        return rolls(chance, state, localPos, worldSeed, carriageIndex) ? trapped(state) : state;
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

    /** {@code nbt} retagged for the trapped chest's block entity type when {@code state} is one. */
    @Nullable
    public static CompoundTag nbtFor(BlockState state, @Nullable CompoundTag nbt) {
        if (nbt == null || !state.is(Blocks.TRAPPED_CHEST) || !nbt.contains(NBT_ID)) return nbt;
        CompoundTag out = nbt.copy();
        out.putString(NBT_ID, TRAPPED_CHEST_ID);
        return out;
    }

    /**
     * The state a chest half about to land at {@code worldPos} goes in as: trapped when its partner
     * half already stands there trapped, so the pair stays joined; else {@code state} unchanged.
     */
    public static BlockState matchPartner(LevelReader level, BlockPos worldPos, BlockState state) {
        if (!isTrappable(state) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return state;
        BlockState partner = level.getBlockState(worldPos.relative(ChestBlock.getConnectedDirection(state)));
        return partner.is(Blocks.TRAPPED_CHEST) && joined(state, partner) ? trapped(state) : state;
    }

    /** After a trapped half lands at {@code worldPos}: swap a plain partner half already placed. */
    public static void swapPartner(LevelAccessor level, BlockPos worldPos, BlockState placed) {
        if (!placed.is(Blocks.TRAPPED_CHEST) || placed.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return;
        BlockPos partnerPos = worldPos.relative(ChestBlock.getConnectedDirection(placed));
        BlockState partner = level.getBlockState(partnerPos);
        if (partner.is(Blocks.CHEST) && joined(placed, partner)) swapInWorld(level, partnerPos, partner);
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
