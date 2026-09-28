package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link NetherTransitionEvents#isNetherFlora} — the core keeps its own Nether flora but still strips
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
        assertFalse(NetherTransitionEvents.isNetherFlora(Blocks.OAK_LOG.defaultBlockState()));
        assertFalse(NetherTransitionEvents.isNetherFlora(Blocks.SPRUCE_LEAVES.defaultBlockState()));
        assertFalse(NetherTransitionEvents.isNetherFlora(Blocks.VINE.defaultBlockState()));
        assertFalse(NetherTransitionEvents.isNetherFlora(Blocks.POPPY.defaultBlockState()));
    }
}
