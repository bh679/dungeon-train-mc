package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.PotDecorations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pick and the guards behind {@link RandomVariantLoot}. Tags are not bound in a unit-test JVM
 * (no datapack load), so the real sherd and horn picks are covered by the in-game Gate 2 flow;
 * here an unbound tag stands in for "a modpack emptied the tag".
 */
final class RandomVariantLootTest {

    private static HolderLookup.Provider registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registries = HolderLookup.Provider.create(Stream.of(
            BuiltInRegistries.ITEM.asLookup(), BuiltInRegistries.INSTRUMENT.asLookup()));
    }

    @Test
    @DisplayName("pickIndex: deterministic, in range, and reaches every option")
    void pickIndex_deterministicAndCovering() {
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 2_000; seed++) {
            int idx = RandomVariantLoot.pickIndex(seed * 0x9E3779B97F4A7C15L, 23);
            assertTrue(idx >= 0 && idx < 23, "index out of range: " + idx);
            assertEquals(idx, RandomVariantLoot.pickIndex(seed * 0x9E3779B97F4A7C15L, 23));
            seen.add(idx);
        }
        assertEquals(23, seen.size(), "every sherd should be reachable");
    }

    @Test
    @DisplayName("empty tag: placeholders bake to EMPTY so the slot is skipped")
    void emptyTag_skipsSlot() {
        assertTrue(RandomVariantLoot.bakeSherd(1L, 4, registries).isEmpty());
        assertTrue(RandomVariantLoot.bakeGoatHorn(1L, registries).isEmpty());
    }

    @Test
    @DisplayName("rollPotSides: about 1 in 80 pots decorated, then 1 in 10 per extra side")
    void rollPotSides_odds() {
        int seeds = 2_000_000;
        int[] bySideCount = new int[5];
        for (long seed = 0; seed < seeds; seed++) {
            int[] sides = RandomVariantLoot.rollPotSides(seed * 0x9E3779B97F4A7C15L, 23);
            if (sides == null) {
                bySideCount[0]++;
                continue;
            }
            assertEquals(4, sides.length);
            int count = 0;
            for (int side : sides) {
                assertTrue(side == RandomVariantLoot.PLAIN_SIDE || (side >= 0 && side < 23), "bad side " + side);
                if (side != RandomVariantLoot.PLAIN_SIDE) count++;
            }
            assertTrue(count >= 1, "a decorated pot has at least one sherd side");
            bySideCount[count]++;
        }
        int decorated = seeds - bySideCount[0];
        assertEquals(seeds / 80.0, decorated, seeds / 80.0 * 0.05, "decorated share");
        assertEquals(decorated * 0.9, bySideCount[1], decorated * 0.9 * 0.05, "one-sided share");
        assertEquals(decorated * 0.09, bySideCount[2], decorated * 0.09 * 0.15, "two-sided share");
        assertTrue(bySideCount[3] > 0, "three sides reachable");
    }

    @Test
    @DisplayName("rollPotSides: deterministic; a further side repeats the last sherd about half the time")
    void rollPotSides_deterministicAndRepeats() {
        int pairs = 0;
        int same = 0;
        for (long seed = 0; seed < 4_000_000 && pairs < 2_000; seed++) {
            long s = seed * 0x9E3779B97F4A7C15L;
            int[] sides = RandomVariantLoot.rollPotSides(s, 23);
            assertArrayEquals(sides, RandomVariantLoot.rollPotSides(s, 23));
            if (sides == null) continue;
            int[] used = java.util.Arrays.stream(sides).filter(i -> i != RandomVariantLoot.PLAIN_SIDE).toArray();
            if (used.length != 2) continue;
            pairs++;
            if (used[0] == used[1]) same++;
        }
        // 50% repeat, plus a new pick landing on the same sherd 1 time in 23.
        double expected = 0.5 + 0.5 / 23;
        assertEquals(expected, same / (double) pairs, 0.05, "repeat share over " + pairs + " two-sided pots");
    }

    @Test
    @DisplayName("decoratePot: empty sherd tag leaves the pot plain")
    void decoratePot_emptyTagPlain() {
        for (long seed = 0; seed < 1_000; seed++) {
            assertEquals(PotDecorations.EMPTY, RandomVariantLoot.decoratePot(seed, registries));
        }
    }

    @Test
    @DisplayName("fillMissingInstrument: keeps a horn's own instrument, ignores other items")
    void fill_keepsExistingAndIgnoresOthers() {
        ItemStack ponder = new ItemStack(Items.GOAT_HORN);
        ponder.set(DataComponents.INSTRUMENT, BuiltInRegistries.INSTRUMENT.getHolderOrThrow(Instruments.PONDER_GOAT_HORN));
        assertTrue(RandomVariantLoot.fillMissingInstrument(ponder, 7L, registries));
        assertTrue(ponder.get(DataComponents.INSTRUMENT).is(Instruments.PONDER_GOAT_HORN));

        ItemStack sherd = new ItemStack(Items.ARCHER_POTTERY_SHERD);
        assertFalse(RandomVariantLoot.fillMissingInstrument(sherd, 7L, registries));
        assertFalse(sherd.has(DataComponents.INSTRUMENT));
    }
}
