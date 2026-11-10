package games.brennan.dungeontrain.editor;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

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
