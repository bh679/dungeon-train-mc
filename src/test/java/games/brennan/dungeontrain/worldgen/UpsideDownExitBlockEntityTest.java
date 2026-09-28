package games.brennan.dungeontrain.worldgen;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which native block entities the exit crossfade leaves in place — beds never, so none float. */
final class UpsideDownExitBlockEntityTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("both halves of every bed colour are dropped")
    void bedsDropped() {
        assertFalse(UpsideDownMirror.keepsNativeBlockEntity(Blocks.YELLOW_BED.defaultBlockState()));
        assertFalse(UpsideDownMirror.keepsNativeBlockEntity(
            Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)));
        assertFalse(UpsideDownMirror.keepsNativeBlockEntity(
            Blocks.WHITE_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT)));
    }

    @Test
    @DisplayName("chests, lecterns and bells keep today's behaviour")
    void otherBlockEntitiesKept() {
        assertTrue(UpsideDownMirror.keepsNativeBlockEntity(Blocks.CHEST.defaultBlockState()));
        assertTrue(UpsideDownMirror.keepsNativeBlockEntity(Blocks.LECTERN.defaultBlockState()));
        assertTrue(UpsideDownMirror.keepsNativeBlockEntity(Blocks.BELL.defaultBlockState()));
    }
}
