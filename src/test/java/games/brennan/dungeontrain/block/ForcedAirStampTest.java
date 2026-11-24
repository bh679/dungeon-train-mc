package games.brennan.dungeontrain.block;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The Forced Air stamp rule: plain air in a live stamp, itself in the editor. The mod's registry is
 * empty under test, so a vanilla block stands in for Forced Air.
 */
class ForcedAirStampTest {

    private static Block standIn;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        standIn = Blocks.STRUCTURE_VOID;
    }

    @Test
    @DisplayName("live stamp: Forced Air becomes minecraft:air")
    void liveBecomesAir() {
        assertSame(Blocks.AIR.defaultBlockState(),
            ForcedAir.forStamp(standIn.defaultBlockState(), true, standIn));
    }

    @Test
    @DisplayName("editor stamp: Forced Air stays itself so the plot saves it back")
    void editorKeepsIt() {
        BlockState forced = standIn.defaultBlockState();
        assertSame(forced, ForcedAir.forStamp(forced, false, standIn));
    }

    @Test
    @DisplayName("every other block, air and water included, is left alone")
    void othersUntouched() {
        for (BlockState s : new BlockState[] {Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), Blocks.KELP.defaultBlockState()}) {
            assertSame(s, ForcedAir.forStamp(s, true, standIn));
        }
        assertSame(null, ForcedAir.forStamp(null, true, standIn));
    }

    @Test
    @DisplayName("unregistered (as under test): the public rule is a no-op, never a crash")
    void unboundIsNoOp() {
        BlockState water = Blocks.WATER.defaultBlockState();
        assertSame(water, ForcedAir.forStamp(water, true));
        assertSame(false, ForcedAir.is(water));
    }
}
