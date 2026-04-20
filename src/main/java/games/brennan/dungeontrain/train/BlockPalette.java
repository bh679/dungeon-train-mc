package games.brennan.dungeontrain.train;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Block-material recipe for one {@link CarriageStyle}. Each surface of a
 * carriage (floor / wall / solid ceiling / glass ceiling / window) resolves
 * to a concrete BlockState via this palette.
 *
 * <p>{@code mossyChance} and {@code mossyWall} together support variegated
 * wall surfaces (e.g. cobblestone with hints of moss): {@link #wallAt} rolls
 * a deterministic hash of the local coordinate and substitutes the mossy
 * variant for ~{@code mossyChance * 100}% of wall cells. When the style has
 * no mossy variant, pass {@code mossyChance = 0f} and {@code mossyWall = null}.
 */
public record BlockPalette(
    BlockState floor,
    BlockState wall,
    BlockState solidCeiling,
    BlockState glassCeiling,
    BlockState window,
    float mossyChance,
    @Nullable BlockState mossyWall
) {

    public BlockState wallAt(int dx, int dy, int dz) {
        if (mossyWall == null || mossyChance <= 0f) return wall;
        int hash = (dx * 73) ^ (dy * 179) ^ (dz * 283);
        int bucket = Math.floorMod(hash, 100);
        return bucket < (mossyChance * 100f) ? mossyWall : wall;
    }

    /**
     * Helper for styles whose floor / wall / solid ceiling are all the same
     * block with no mossy variance — glass ceiling and window default to
     * vanilla glass.
     */
    public static BlockPalette uniform(Block solid, Block glass) {
        BlockState s = solid.defaultBlockState();
        BlockState g = glass.defaultBlockState();
        return new BlockPalette(s, s, s, g, g, 0f, null);
    }
}
