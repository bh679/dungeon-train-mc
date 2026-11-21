package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every cracked block breaks slightly faster; everything else keeps its speed. */
final class CrackedBrickBreakSpeedEventsTest {

    /** Each vanilla cracked block paired with its whole sibling. */
    private static final List<Block[]> CRACKED_AND_WHOLE = List.of(
        new Block[] {Blocks.CRACKED_STONE_BRICKS, Blocks.STONE_BRICKS},
        new Block[] {Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_BRICKS},
        new Block[] {Blocks.CRACKED_DEEPSLATE_TILES, Blocks.DEEPSLATE_TILES},
        new Block[] {Blocks.CRACKED_NETHER_BRICKS, Blocks.NETHER_BRICKS},
        new Block[] {Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS});

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("every cracked block breaks faster than its whole sibling, but only slightly")
    void crackedFaster() {
        for (Block[] pair : CRACKED_AND_WHOLE) {
            float cracked = CrackedBrickBreakSpeedEvents.adjustedSpeed(pair[0].defaultBlockState(), 4.0f);
            float whole = CrackedBrickBreakSpeedEvents.adjustedSpeed(pair[1].defaultBlockState(), 4.0f);
            assertEquals(4.0f, whole, pair[1] + " should be unchanged");
            assertTrue(cracked > whole && cracked <= 5.0f, pair[0] + " got " + cracked);
        }
    }

    @Test
    @DisplayName("vanilla cracked blocks have their whole sibling's hardness, so the multiplier is the only difference")
    void vanillaHardnessEqual() {
        for (Block[] pair : CRACKED_AND_WHOLE) {
            assertEquals(pair[1].defaultDestroyTime(), pair[0].defaultDestroyTime(), pair[0].toString());
        }
    }

    @Test
    @DisplayName("non-cracked blocks are unchanged, including infested cracked bricks")
    void othersUnchanged() {
        for (Block b : List.of(Blocks.MOSSY_STONE_BRICKS, Blocks.STONE, Blocks.INFESTED_CRACKED_STONE_BRICKS)) {
            assertEquals(4.0f, CrackedBrickBreakSpeedEvents.adjustedSpeed(b.defaultBlockState(), 4.0f), b.toString());
        }
    }
}
