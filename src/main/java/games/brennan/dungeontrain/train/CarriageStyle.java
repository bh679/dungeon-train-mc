package games.brennan.dungeontrain.train;

import net.minecraft.world.level.block.Blocks;

/**
 * Block-material variant for a carriage. Each style owns a {@link BlockPalette}
 * that resolves floor / wall / ceiling / window blocks. Adding a style = adding
 * one enum constant with its palette.
 */
public enum CarriageStyle {
    STONE(BlockPalette.uniform(Blocks.STONE_BRICKS, Blocks.GLASS)),
    COBBLESTONE_MOSSY(new BlockPalette(
        Blocks.COBBLESTONE.defaultBlockState(),
        Blocks.COBBLESTONE.defaultBlockState(),
        Blocks.COBBLESTONE.defaultBlockState(),
        Blocks.GLASS.defaultBlockState(),
        Blocks.GLASS.defaultBlockState(),
        0.20f,
        Blocks.MOSSY_COBBLESTONE.defaultBlockState()
    )),
    BRICK(BlockPalette.uniform(Blocks.BRICKS, Blocks.GLASS)),
    SANDSTONE(BlockPalette.uniform(Blocks.SANDSTONE, Blocks.GLASS)),
    DEEPSLATE(BlockPalette.uniform(Blocks.DEEPSLATE_BRICKS, Blocks.GLASS)),
    OAK(BlockPalette.uniform(Blocks.OAK_PLANKS, Blocks.GLASS)),
    SPRUCE(BlockPalette.uniform(Blocks.SPRUCE_PLANKS, Blocks.GLASS)),
    BIRCH(BlockPalette.uniform(Blocks.BIRCH_PLANKS, Blocks.GLASS)),
    JUNGLE(BlockPalette.uniform(Blocks.JUNGLE_PLANKS, Blocks.GLASS)),
    ACACIA(BlockPalette.uniform(Blocks.ACACIA_PLANKS, Blocks.GLASS)),
    DARK_OAK(BlockPalette.uniform(Blocks.DARK_OAK_PLANKS, Blocks.GLASS)),
    MANGROVE(BlockPalette.uniform(Blocks.MANGROVE_PLANKS, Blocks.GLASS)),
    CHERRY(BlockPalette.uniform(Blocks.CHERRY_PLANKS, Blocks.GLASS)),
    CRIMSON(BlockPalette.uniform(Blocks.CRIMSON_PLANKS, Blocks.GLASS)),
    WARPED(BlockPalette.uniform(Blocks.WARPED_PLANKS, Blocks.GLASS)),
    BAMBOO(BlockPalette.uniform(Blocks.BAMBOO_PLANKS, Blocks.GLASS));

    private final BlockPalette palette;

    CarriageStyle(BlockPalette palette) {
        this.palette = palette;
    }

    public BlockPalette palette() {
        return palette;
    }
}
