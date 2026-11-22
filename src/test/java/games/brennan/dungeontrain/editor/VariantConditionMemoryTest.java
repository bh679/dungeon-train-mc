package games.brennan.dungeontrain.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link VariantConditionMemory}: what the If popup prefills an empty row with. */
class VariantConditionMemoryTest {

    private static final VariantCondition NONE = VariantCondition.NONE;
    private static final VariantCondition STONE = new VariantCondition(VariantCondition.ALL_SIDES, 0,
        List.of(ResourceLocation.withDefaultNamespace("stone")));
    private static final VariantCondition OAK = new VariantCondition(VariantCondition.ALL_SIDES, 0,
        List.of(ResourceLocation.withDefaultNamespace("oak_planks")), true, true);

    @BeforeEach
    void reset() {
        VariantConditionMemory.clear();
    }

    @Test
    void recordIgnoresAConditionWithNoValues() {
        VariantConditionMemory.record("k", STONE);
        VariantConditionMemory.record("k", NONE);
        assertEquals(STONE, VariantConditionMemory.recall("k"));
    }

    @Test
    void recallIsPerKeyAndKeepsTheLatest() {
        VariantConditionMemory.record("a", STONE);
        VariantConditionMemory.record("a", OAK);
        assertEquals(OAK, VariantConditionMemory.recall("a"));
        assertNull(VariantConditionMemory.recall("b"));
    }

    @Test
    void keyUsesTheLockGroupWhenThereIsOne() {
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        assertEquals(VariantConditionMemory.key("p", a, 7), VariantConditionMemory.key("p", b, 7));
        assertNotEquals(VariantConditionMemory.key("p", a, 0), VariantConditionMemory.key("p", b, 0));
    }

    @Test
    void rememberedConditionBeatsTheNearestRow() {
        assertEquals(OAK, VariantConditionMemory.prefillFor(OAK, List.of(STONE, NONE), 1));
    }

    @Test
    void fallbackPrefersTheRowAboveThenBelow() {
        assertEquals(STONE, VariantConditionMemory.prefillFor(null, List.of(OAK, STONE, NONE, OAK), 2));
        assertEquals(OAK, VariantConditionMemory.fallback(List.of(NONE, NONE, OAK), 0));
    }

    @Test
    void nothingToCopyGivesNull() {
        assertNull(VariantConditionMemory.prefillFor(null, List.of(NONE, NONE), 1));
    }
}
