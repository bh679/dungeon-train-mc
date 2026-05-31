package games.brennan.dungeontrain.tunnel;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Centralised block palette for {@link TunnelGenerator} and the
 * underground-material predicate used to decide whether a column qualifies
 * for tunnel placement.
 */
public final class TunnelPalette {

    public static final BlockState WALL        = Blocks.STONE_BRICKS.defaultBlockState();
    public static final BlockState CEILING     = Blocks.STONE_BRICKS.defaultBlockState();
    public static final BlockState FLOOR       = Blocks.STONE_BRICKS.defaultBlockState();
    public static final BlockState PYRAMID     = Blocks.STONE_BRICKS.defaultBlockState();
    public static final Block      STAIRS      = Blocks.STONE_BRICK_STAIRS;
    public static final BlockState AIR         = Blocks.AIR.defaultBlockState();
    public static final BlockState SEA_LANTERN = Blocks.SEA_LANTERN.defaultBlockState();

    private TunnelPalette() {}

    /**
     * True when {@code s} is a natural underground / bulk-terrain block in
     * any of the three vanilla dimensions:
     *
     * <ul>
     *   <li><b>Overworld</b> — stone family (stone/granite/diorite/andesite
     *       + deepslate variants), tuff, dirt family (dirt/coarse dirt/
     *       rooted dirt/podzol/grass block's subsoil), gravel, sandstone /
     *       red-sandstone, any of the common ores that replace those, and
     *       the clay family (terracotta + colored variants, red sand,
     *       mud / packed mud).</li>
     *   <li><b>Nether</b> — netherrack, basalt (incl. smooth),
     *       blackstone, gilded blackstone, soul sand, soul soil, magma
     *       block, glowstone, shroomlight, nether-ore family
     *       (nether_gold_ore, nether_quartz_ore, ancient_debris),
     *       nylium (crimson + warped), wart blocks (nether + warped).</li>
     *   <li><b>End</b> — end_stone. {@code end_stone_bricks} is excluded
     *       on purpose: it's a player-crafted variant and only appears
     *       naturally in end-city structures, well above the corridor.</li>
     * </ul>
     *
     * <p>Leaves, wood, logs, plants, water, lava, and air all return
     * false — encountering any of those above the would-be tunnel ceiling
     * disqualifies a column because it means daylight or biome-surface
     * features are within the "2 blocks overhead" margin. Lava is rejected
     * via the {@link net.minecraft.world.level.material.FluidState} guard,
     * so nether lava lakes correctly disqualify too.</p>
     *
     * <p>The predicate takes no dimension parameter: it's safe to share
     * the same logic across dimensions because each {@code WorldGenLevel}
     * caller is inherently dimension-scoped (the chunk only contains
     * blocks from its own dimension) and the three block sets don't
     * naturally overlap.</p>
     */
    public static boolean isUndergroundMaterial(BlockState s) {
        if (s.isAir()) return false;
        if (!s.getFluidState().isEmpty()) return false;

        // Overworld.
        if (s.is(BlockTags.BASE_STONE_OVERWORLD)) return true;
        if (s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) return true;
        if (s.is(BlockTags.STONE_ORE_REPLACEABLES)) return true;
        if (s.is(BlockTags.DIRT)) return true;

        if (s.is(Blocks.TUFF)) return true;
        if (s.is(Blocks.GRAVEL)) return true;
        if (s.is(Blocks.CLAY)) return true;
        if (s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)) return true;

        if (isClayFamilyMaterial(s)) return true;

        if (s.is(BlockTags.COAL_ORES)) return true;
        if (s.is(BlockTags.IRON_ORES)) return true;
        if (s.is(BlockTags.COPPER_ORES)) return true;
        if (s.is(BlockTags.GOLD_ORES)) return true;
        if (s.is(BlockTags.REDSTONE_ORES)) return true;
        if (s.is(BlockTags.LAPIS_ORES)) return true;
        if (s.is(BlockTags.DIAMOND_ORES)) return true;
        if (s.is(BlockTags.EMERALD_ORES)) return true;

        // Nether.
        if (s.is(BlockTags.BASE_STONE_NETHER)) return true;  // netherrack, basalt, blackstone
        if (s.is(BlockTags.NYLIUM)) return true;             // crimson_nylium, warped_nylium
        if (s.is(BlockTags.WART_BLOCKS)) return true;        // nether + warped wart blocks

        if (s.is(Blocks.SMOOTH_BASALT)) return true;
        if (s.is(Blocks.SOUL_SAND)) return true;
        if (s.is(Blocks.SOUL_SOIL)) return true;
        if (s.is(Blocks.MAGMA_BLOCK)) return true;
        if (s.is(Blocks.GLOWSTONE)) return true;
        if (s.is(Blocks.SHROOMLIGHT)) return true;
        if (s.is(Blocks.GILDED_BLACKSTONE)) return true;
        if (s.is(Blocks.NETHER_GOLD_ORE)) return true;
        if (s.is(Blocks.NETHER_QUARTZ_ORE)) return true;
        if (s.is(Blocks.ANCIENT_DEBRIS)) return true;

        // End.
        if (s.is(Blocks.END_STONE)) return true;

        return false;
    }

    /**
     * Blocks that define clay-based biomes — badlands terracotta layers (plain
     * plus all 16 colours), badlands red sand surface, and mangrove-swamp mud.
     * Vanilla MC 1.20.1 does not group these under a single block tag, so the
     * list is explicit. A future iteration can switch to a {@code dungeontrain:
     * underground_material} tag so modded datapacks can inject their own
     * biome-surface blocks.
     */
    private static boolean isClayFamilyMaterial(BlockState s) {
        return s.is(Blocks.TERRACOTTA)
            || s.is(Blocks.WHITE_TERRACOTTA)
            || s.is(Blocks.ORANGE_TERRACOTTA)
            || s.is(Blocks.MAGENTA_TERRACOTTA)
            || s.is(Blocks.LIGHT_BLUE_TERRACOTTA)
            || s.is(Blocks.YELLOW_TERRACOTTA)
            || s.is(Blocks.LIME_TERRACOTTA)
            || s.is(Blocks.PINK_TERRACOTTA)
            || s.is(Blocks.GRAY_TERRACOTTA)
            || s.is(Blocks.LIGHT_GRAY_TERRACOTTA)
            || s.is(Blocks.CYAN_TERRACOTTA)
            || s.is(Blocks.PURPLE_TERRACOTTA)
            || s.is(Blocks.BLUE_TERRACOTTA)
            || s.is(Blocks.BROWN_TERRACOTTA)
            || s.is(Blocks.GREEN_TERRACOTTA)
            || s.is(Blocks.RED_TERRACOTTA)
            || s.is(Blocks.BLACK_TERRACOTTA)
            || s.is(Blocks.RED_SAND)
            || s.is(Blocks.MUD)
            || s.is(Blocks.PACKED_MUD);
    }
}
