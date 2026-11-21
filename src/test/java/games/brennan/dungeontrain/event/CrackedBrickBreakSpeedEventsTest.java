package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cracked stone bricks break slightly faster; everything else keeps its speed. */
final class CrackedBrickBreakSpeedEventsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("cracked stone bricks break faster, but only slightly")
    void crackedFaster() {
        float speed = CrackedBrickBreakSpeedEvents.adjustedSpeed(Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), 4.0f);
        assertTrue(speed > 4.0f && speed <= 5.0f, "got " + speed);
    }

    @Test
    @DisplayName("stone bricks and other blocks are unchanged")
    void othersUnchanged() {
        assertEquals(4.0f, CrackedBrickBreakSpeedEvents.adjustedSpeed(Blocks.STONE_BRICKS.defaultBlockState(), 4.0f));
        assertEquals(4.0f, CrackedBrickBreakSpeedEvents.adjustedSpeed(Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 4.0f));
        assertEquals(4.0f, CrackedBrickBreakSpeedEvents.adjustedSpeed(Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState(), 4.0f));
    }

    @Test
    @DisplayName("cracked hardness matches stone bricks in vanilla, so the multiplier is the only difference")
    void vanillaHardnessEqual() {
        assertEquals(Blocks.STONE_BRICKS.defaultDestroyTime(), Blocks.CRACKED_STONE_BRICKS.defaultDestroyTime());
    }
}
