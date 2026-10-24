package games.brennan.dungeontrain.perks.effects.hoarder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the Hoarder Looting rules: rank% chance of one extra item per kill, copied from one of the drops. */
final class HoarderLootingTest {

    private static final int SAMPLES = 20_000;
    private static final double TOLERANCE = 0.01;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static double hitRate(int rank) {
        RandomSource random = RandomSource.create(7L);
        int hits = 0;
        for (int i = 0; i < SAMPLES; i++) if (HoarderLooting.rollsExtra(rank, random)) hits++;
        return hits / (double) SAMPLES;
    }

    @Test
    @DisplayName("rank 0 never drops extra; rank 100 and above always does")
    void endsOfTheScale() {
        assertEquals(0.0, hitRate(0));
        assertEquals(0.0, hitRate(-1));
        assertEquals(1.0, hitRate(100));
        assertEquals(1.0, hitRate(250));
        assertEquals(1.0, HoarderLooting.chanceFor(500));
    }

    @Test
    @DisplayName("rank 1 is 1%, rank 10 is 10%, rank 50 is half")
    void rankIsPercent() {
        assertEquals(0.01, hitRate(1), TOLERANCE);
        assertEquals(0.10, hitRate(10), TOLERANCE);
        assertEquals(0.50, hitRate(50), TOLERANCE);
    }

    @Test
    @DisplayName("the extra item is a single copy of one of the non-empty drops")
    void pickExtra_copiesOneDrop() {
        List<ItemStack> drops = List.of(ItemStack.EMPTY, new ItemStack(Items.ROTTEN_FLESH, 3), new ItemStack(Items.IRON_INGOT));
        RandomSource random = RandomSource.create(3L);
        boolean sawFlesh = false;
        boolean sawIron = false;
        for (int i = 0; i < 200; i++) {
            ItemStack extra = HoarderLooting.pickExtra(drops, random);
            assertEquals(1, extra.getCount());
            if (extra.is(Items.ROTTEN_FLESH)) sawFlesh = true;
            else if (extra.is(Items.IRON_INGOT)) sawIron = true;
            else throw new AssertionError("unexpected extra " + extra);
        }
        assertTrue(sawFlesh && sawIron, "both drops should be picked over 200 rolls");
        assertEquals(3, drops.get(1).getCount(), "source stacks are not mutated");
    }

    @Test
    @DisplayName("nothing to copy means no extra item")
    void pickExtra_emptyDrops() {
        assertTrue(HoarderLooting.pickExtra(List.of(), RandomSource.create(1L)).isEmpty());
        assertTrue(HoarderLooting.pickExtra(List.of(ItemStack.EMPTY), RandomSource.create(1L)).isEmpty());
    }
}
