package games.brennan.dungeontrain.track;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Centralised block palette for {@link TrackGenerator}. One file to change
 * if we ever want a wooden trestle-style bridge or a different rail variant.
 */
public final class TrackPalette {

    public static final BlockState BED = Blocks.STONE_BRICKS.defaultBlockState();
    public static final BlockState PILLAR = Blocks.STONE_BRICKS.defaultBlockState();
    /**
     * Block placed in the curved span between two tall pillars — visually fills
     * the area above the arch opening and below the bed. Kept as a separate
     * constant from {@link #PILLAR} so the arch can later be restyled (mossy
     * stone brick, chiseled, etc.) without touching generator logic.
     */
    public static final BlockState ARCH = Blocks.STONE_BRICKS.defaultBlockState();
    public static final BlockState RAIL = Blocks.RAIL.defaultBlockState()
        .setValue(RailBlock.SHAPE, RailShape.EAST_WEST);

    private TrackPalette() {}
}
