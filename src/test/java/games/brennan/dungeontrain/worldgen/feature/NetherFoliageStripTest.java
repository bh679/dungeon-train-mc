package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Nether-band foliage strip's two cheap gates: the per-block cached predicates and the section palette
 * skip that keeps netherrack/lava/air sections out of the 4096-block scan.
 */
final class NetherFoliageStripTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /*
     * Tag-backed answers (#minecraft:leaves/logs/saplings/flowers, crimson/warped stems) are NOT pinned here:
     * BlockState.is(BlockTags.X) needs tags bound by a loaded datapack, which the unit-test bootstrap has not
     * done (see NetherTransitionEventsTest). Vine is matched by block, and Nether-flora's namespace rule needs
     * no tags, so those carry the tests; the tag branches are covered by the in-game Gate 2 check.
     */

    @Test
    @DisplayName("vine is strippable by block; rock, fluids, air and netherrack are not")
    void strippablePredicate() {
        assertTrue(StrippableFoliage.isStrippable(Blocks.VINE.defaultBlockState()));
        assertFalse(StrippableFoliage.isStrippable(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(StrippableFoliage.isStrippable(Blocks.STONE.defaultBlockState()));
        assertFalse(StrippableFoliage.isStrippable(Blocks.WATER.defaultBlockState()));
        assertFalse(StrippableFoliage.isStrippable(Blocks.LAVA.defaultBlockState()));
        assertFalse(StrippableFoliage.isStrippable(Blocks.AIR.defaultBlockState()));
    }

    @Test
    @DisplayName("the cached answer matches the uncached lookup for every registered block, before and after reset")
    void cacheMatchesUncachedLookup() {
        StrippableFoliage.reset();
        for (Block block : BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            boolean expected = state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.LOGS)
                    || state.is(Blocks.VINE)
                    || state.is(BlockTags.SAPLINGS)
                    || state.is(BlockTags.SMALL_FLOWERS)
                    || state.is(BlockTags.TALL_FLOWERS);
            assertEquals(expected, StrippableFoliage.isStrippable(state), block.toString());
            assertEquals(expected, StrippableFoliage.isStrippable(state), block + " (cached)");
            assertEquals(expected, NetherTransitionFeature.isStrippableFoliage(state), block + " (delegate)");
        }
        StrippableFoliage.reset();
        assertTrue(StrippableFoliage.isStrippable(Blocks.VINE.defaultBlockState()), "survives a tag reload reset");
    }

    @Test
    @DisplayName("vanilla blocks are never Nether flora by namespace")
    void netherFloraPredicate() {
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.VINE.defaultBlockState()));
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.NETHERRACK.defaultBlockState()));
    }

    @Test
    @DisplayName("a spilled overworld vine is stripped in the crossfade and in the core; rock and air never")
    void shouldStripFoldsInTheCoreRule() {
        BlockState vine = Blocks.VINE.defaultBlockState();
        assertTrue(NetherFoliageStrip.shouldStrip(vine, false), "crossfade strips it");
        assertTrue(NetherFoliageStrip.shouldStrip(vine, true), "vanilla foliage still goes in the core");
        assertFalse(NetherFoliageStrip.shouldStrip(Blocks.AIR.defaultBlockState(), false));
        assertFalse(NetherFoliageStrip.shouldStrip(Blocks.NETHERRACK.defaultBlockState(), false));
        assertFalse(NetherFoliageStrip.shouldStrip(Blocks.NETHERRACK.defaultBlockState(), true));
    }

    @Test
    @DisplayName("a section whose palette holds no foliage is skipped; one vine block makes it a candidate")
    void paletteSkip() {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockState fill = (x + z) % 5 == 0 ? Blocks.LAVA.defaultBlockState() : Blocks.NETHERRACK.defaultBlockState();
                    states.set(x, y, z, fill);
                }
            }
        }
        assertFalse(states.maybeHas(StrippableFoliage::isStrippable), "netherrack + lava + air: nothing to scan");

        states.set(3, 9, 4, Blocks.VINE.defaultBlockState());
        assertTrue(states.maybeHas(StrippableFoliage::isStrippable), "one vine block in the palette: scan it");
    }

    @Test
    @DisplayName("packed positions round-trip through the plan")
    void packRoundTrip() {
        int packed = NetherFoliageStrip.pack(23, 15, 14, 13);
        assertEquals(23, packed >>> 12);
        assertEquals(15, (packed >>> 8) & 0xF);
        assertEquals(14, (packed >>> 4) & 0xF);
        assertEquals(13, packed & 0xF);
        NetherFoliageStrip.Plan plan = new NetherFoliageStrip.Plan(new int[] {packed});
        assertEquals(1, plan.size());
    }
}
