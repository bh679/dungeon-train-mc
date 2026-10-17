package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Decoration never replaces a standing block in the core pass — vine anchors and chains stay whole. */
final class StandingBlockKeepTest {

    private static final BlockPos POS = new BlockPos(28200, 165, -85);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static boolean keeps(BlockState existing, BlockState incoming) {
        // Shape lookups for these blocks never read the level; the empty getter satisfies the signature.
        return StandingBlockKeep.keeps(existing, incoming, EmptyBlockGetter.INSTANCE, POS);
    }

    @Test
    @DisplayName("vines (the BetterNether moss stand-in) over shroomlight: the shroomlight stays")
    void decorationOverAnchorIsDropped() {
        // wall_moss is a BetterNether block, not on the unit-test classpath; weeping vines have the same
        // (empty) collision box and are the other overwriter seen in the probe.
        assertTrue(keeps(Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.WEEPING_VINES.defaultBlockState()));
        assertTrue(keeps(Blocks.CRIMSON_STEM.defaultBlockState(), Blocks.CRIMSON_ROOTS.defaultBlockState()));
        assertTrue(keeps(Blocks.CRIMSON_HYPHAE.defaultBlockState(), Blocks.CRIMSON_FUNGUS.defaultBlockState()));
        // wall moss keeps a thin collision slab (BetterNether BlockPlantWall#getShape); a ladder is the vanilla twin
        assertTrue(keeps(Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.LADDER.defaultBlockState()));
    }

    @Test
    @DisplayName("decoration over air is written")
    void decorationOverAirIsWritten() {
        assertFalse(keeps(Blocks.AIR.defaultBlockState(), Blocks.WEEPING_VINES.defaultBlockState()));
        assertFalse(keeps(Blocks.AIR.defaultBlockState(), Blocks.CRIMSON_ROOTS.defaultBlockState()));
    }

    @Test
    @DisplayName("a full block over a full block is written (trunks still cross)")
    void fullBlockOverFullBlockIsWritten() {
        assertFalse(keeps(Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.CRIMSON_STEM.defaultBlockState()));
        assertFalse(keeps(Blocks.NETHERRACK.defaultBlockState(), Blocks.CRIMSON_HYPHAE.defaultBlockState()));
    }

    @Test
    @DisplayName("air is a deliberate template write and always passes")
    void airIsWritten() {
        assertFalse(keeps(Blocks.NETHERRACK.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertFalse(keeps(Blocks.SHROOMLIGHT.defaultBlockState(), Blocks.AIR.defaultBlockState()));
    }

    @Test
    @DisplayName("decoration over a vine segment is dropped (the chain under an anchor stays whole)")
    void decorationOverVineSegmentIsDropped() {
        assertTrue(keeps(Blocks.WEEPING_VINES_PLANT.defaultBlockState(), Blocks.WEEPING_VINES.defaultBlockState()));
        assertTrue(keeps(Blocks.WEEPING_VINES_PLANT.defaultBlockState(), Blocks.CRIMSON_ROOTS.defaultBlockState()));
    }

    @Test
    @DisplayName("decoration over other decoration (grass, a ladder standing in for wall moss) is written")
    void decorationOverDecorationIsWritten() {
        assertFalse(keeps(Blocks.SHORT_GRASS.defaultBlockState(), Blocks.WEEPING_VINES.defaultBlockState()));
        assertFalse(keeps(Blocks.LADDER.defaultBlockState(), Blocks.WEEPING_VINES_PLANT.defaultBlockState()));
        assertFalse(keeps(Blocks.CRIMSON_ROOTS.defaultBlockState(), Blocks.LADDER.defaultBlockState()));
    }

    @Test
    @DisplayName("a full block over a vine segment is written (the chain below hangs from it)")
    void fullBlockOverVineIsWritten() {
        assertFalse(keeps(Blocks.WEEPING_VINES_PLANT.defaultBlockState(), Blocks.CRIMSON_STEM.defaultBlockState()));
    }
}
