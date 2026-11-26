package games.brennan.dungeontrain.perks.effects.knight;

import games.brennan.dungeontrain.perks.aura.AuraLadder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Debuff Aura's ladder against the design's table (2026-10-29); the ticking is the shared aura's. */
final class DebuffAuraTest {

    private static int[] ranges(int rank) {
        return DebuffAura.shape(rank).ranges();
    }

    @Test
    @DisplayName("nothing at 0 points")
    void offAtZero() {
        assertEquals(0, DebuffAura.shape(0).count());
    }

    @Test
    @DisplayName("the design's table, point by point")
    void table() {
        assertArrayEquals(new int[] {1}, ranges(1));
        assertArrayEquals(new int[] {4}, ranges(4));
        assertArrayEquals(new int[] {4, 1}, ranges(5));
        assertArrayEquals(new int[] {4, 4}, ranges(8));
        assertArrayEquals(new int[] {5, 5}, ranges(9));
        assertArrayEquals(new int[] {5, 5, 1}, ranges(10));
        assertArrayEquals(new int[] {5, 5, 5}, ranges(14));
        assertArrayEquals(new int[] {6, 6, 6}, ranges(15));
        assertArrayEquals(new int[] {6, 6, 6, 1}, ranges(16));
        assertArrayEquals(new int[] {6, 6, 6, 6}, ranges(21));
        assertArrayEquals(new int[] {7, 7, 7, 7}, ranges(22));
        assertArrayEquals(new int[] {7, 7, 7, 7, 1}, ranges(23));
        assertArrayEquals(new int[] {7, 7, 7, 7, 7}, ranges(29));
    }

    @Test
    @DisplayName("from 30: +1 to all, then a level round, alternating, no cap")
    void alternating() {
        assertArrayEquals(new int[] {8, 8, 8, 8, 8}, ranges(30));
        assertEquals(0, DebuffAura.shape(30).levelUps());
        assertArrayEquals(new int[] {8, 8, 8, 8, 8}, ranges(31));
        assertEquals(1, DebuffAura.shape(31).levelUps());
        assertArrayEquals(new int[] {9, 9, 9, 9, 9}, ranges(32));
        assertEquals(2, DebuffAura.shape(33).levelUps());
        AuraLadder.Shape far = DebuffAura.shape(130);
        assertEquals(58, far.range(0));
        assertEquals(50, far.levelUps());
    }

    @Test
    @DisplayName("each point after the first changes something")
    void everyPointCounts() {
        for (int r = 1; r < 80; r++) {
            AuraLadder.Shape a = DebuffAura.shape(r), b = DebuffAura.shape(r + 1);
            boolean same = java.util.Arrays.equals(a.ranges(), b.ranges()) && a.levelUps() == b.levelUps();
            assertEquals(false, same, "point " + (r + 1));
        }
    }

    @Test
    @DisplayName("aura Wither leaves half a heart")
    void witherFloor() {
        assertEquals(1F, DebuffAuraEvents.flooredDamage(2F, 1F));
        assertEquals(0.5F, DebuffAuraEvents.flooredDamage(1.5F, 1F));
        assertEquals(0F, DebuffAuraEvents.flooredDamage(1F, 1F));
        assertEquals(0F, DebuffAuraEvents.flooredDamage(0.5F, 1F));
    }

    @Test
    @DisplayName("Glowing skips level rounds")
    void glowingNoLevels() {
        assertEquals(true, DebuffAura.NO_LEVELS.contains(net.minecraft.resources.ResourceLocation.withDefaultNamespace("glowing")));
        assertEquals(5, DebuffAura.POOL.size());
    }
}
