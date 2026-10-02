package games.brennan.dungeontrain.worldgen.feature;

import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link NetherCoreSurvivalSweep}'s pure parts — which states it tests and the swept area.
 * The {@code canSurvive} removal itself needs a level and is verified headlessly (bud scan).
 */
final class NetherCoreSurvivalSweepTest {

    @Test
    @DisplayName("fragile: plants, fire, vines are tested; terrain, air and fluids never are")
    void fragileStates() {
        assertTrue(NetherCoreSurvivalSweep.isFragile(Blocks.CRIMSON_ROOTS.defaultBlockState()));
        assertTrue(NetherCoreSurvivalSweep.isFragile(Blocks.FIRE.defaultBlockState()));
        assertTrue(NetherCoreSurvivalSweep.isFragile(Blocks.WEEPING_VINES.defaultBlockState()));
        assertTrue(NetherCoreSurvivalSweep.isFragile(Blocks.CRIMSON_FUNGUS.defaultBlockState()));
        assertFalse(NetherCoreSurvivalSweep.isFragile(Blocks.NETHERRACK.defaultBlockState()));
        assertFalse(NetherCoreSurvivalSweep.isFragile(Blocks.BASALT.defaultBlockState()));
        assertFalse(NetherCoreSurvivalSweep.isFragile(Blocks.AIR.defaultBlockState()));
        assertFalse(NetherCoreSurvivalSweep.isFragile(Blocks.LAVA.defaultBlockState()));
    }

    @Test
    @DisplayName("area: the 3x3 region less a one-block margin, so canSurvive reads stay inside it")
    void sweptArea() {
        assertEquals(4640 - 15, NetherCoreSurvivalSweep.sweepMin(4640));
        assertEquals(4640 + 30, NetherCoreSurvivalSweep.sweepMax(4640));
        assertEquals(-16 - 15, NetherCoreSurvivalSweep.sweepMin(-16));
        assertEquals(-16 + 30, NetherCoreSurvivalSweep.sweepMax(-16));
    }
}
