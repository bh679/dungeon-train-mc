package games.brennan.dungeontrain.ship.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.function.LongPredicate;

/**
 * The pure arithmetic behind {@code EntityOnPosNoSyncLoadMixin}: which chunk an on-pos lands in,
 * and the vanilla answer for an entity with no supporting block.
 */
public final class OnPosFallback {

    private OnPosFallback() {}

    /**
     * Vanilla {@code Entity.getOnPos(float)} when {@code mainSupportingBlockPos} is empty: the block
     * {@code yOffset} below the feet. Needs no block read, so it can never load a chunk.
     */
    public static BlockPos unsupported(double x, double y, double z, float yOffset) {
        return new BlockPos(Mth.floor(x), Mth.floor(y - (double) yOffset), Mth.floor(z));
    }

    /** True when the chunk holding {@code pos} is loaded, asked of {@code loaded} by {@link ChunkPos#asLong} key. */
    public static boolean chunkLoadedFor(BlockPos pos, LongPredicate loaded) {
        return loaded.test(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
    }
}
