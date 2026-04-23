package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared helpers for the train / track / tunnel carving hot paths that must
 * clear or replace blocks without spawning particles, playing break sounds,
 * or dropping items.
 *
 * <p>{@code Level.destroyBlock(pos, false)} still fires {@code levelEvent(2001,
 * ...)}  — break particles + sound — so it's not silent. Plain
 * {@code Level.setBlock(pos, state, UPDATE_CLIENTS)} is silent for regular
 * terrain, but when the replaced block has a block entity (chest, hopper,
 * furnace, brewing stand, barrel, shulker box — all common in villages,
 * mineshafts, strongholds, dungeons) vanilla {@code BlockBehaviour.onRemove}
 * overrides still drop container contents as item entities.</p>
 *
 * <p>This helper removes the block entity first so the {@code onRemove}
 * subclass overrides see no container, then writes the new state with
 * {@link Block#UPDATE_CLIENTS} only.</p>
 */
public final class SilentBlockOps {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private SilentBlockOps() {}

    /**
     * Replace the block at {@code pos} with {@code newState} silently — no
     * break particles, no break sound, no item drops, no container spill.
     * Callers are responsible for all other guards (ship-owned positions,
     * chunk loaded, idempotence).
     */
    public static void setBlockSilent(ServerLevel level, BlockPos pos, BlockState newState) {
        BlockState existing = level.getBlockState(pos);
        if (existing.hasBlockEntity()) {
            level.removeBlockEntity(pos);
        }
        level.setBlock(pos, newState, Block.UPDATE_CLIENTS);
    }

    /** Clear the block at {@code pos} to air silently. */
    public static void clearBlockSilent(ServerLevel level, BlockPos pos) {
        setBlockSilent(level, pos, AIR);
    }
}
