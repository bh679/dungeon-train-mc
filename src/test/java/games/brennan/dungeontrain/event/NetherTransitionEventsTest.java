package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import games.brennan.dungeontrain.worldgen.feature.StrippableFoliage;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link StrippableFoliage#isNetherFlora} (the Nether-band strip's core rule) — the core keeps its own Nether flora but still strips
 * overworld trees spilled into it.
 *
 * <p><b>Tag-backed branches are NOT tested here.</b> {@code BlockState.is(BlockTags.X)} needs tags bound
 * by a loaded datapack, which the unit-test bootstrap has not done, so the crimson/warped stem and wart
 * branches are covered by the headless region scan instead. What is pinned here is the other half of the
 * contract: vanilla overworld wood and leaves are never mistaken for Nether flora.</p>
 */
class NetherTransitionEventsTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("overworld logs and leaves are still stripped from the core")
    void overworldWoodIsNotNetherFlora() {
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.SPRUCE_LEAVES.defaultBlockState()));
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.VINE.defaultBlockState()));
        assertFalse(StrippableFoliage.isNetherFlora(Blocks.POPPY.defaultBlockState()));
    }
}
