package games.brennan.dungeontrain.editor;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional stored potion on a {@link ContainerContentsEntry}: absent by default on every
 * existing constructor (old data reads unchanged), carried through every {@code with*} copy,
 * and settable/clearable via {@link ContainerContentsEntry#withPotion}.
 */
final class ContainerContentsEntryPotionTest {

    private static final ResourceLocation POTION_ITEM = ResourceLocation.fromNamespaceAndPath("minecraft", "potion");
    private static final ResourceLocation HEALING = ResourceLocation.fromNamespaceAndPath("minecraft", "healing");

    @Test
    void legacyConstructors_haveNoPotion() {
        assertNull(new ContainerContentsEntry(POTION_ITEM, 1, 1).potionId());
        assertNull(new ContainerContentsEntry(POTION_ITEM, 1, 1, true, 100, true, 25).potionId());
        assertNull(new ContainerContentsEntry(POTION_ITEM, 1, 1, true, 100, true, 25,
            ContainerContentsEntry.SLOT_AUTO).potionId());
        assertFalse(new ContainerContentsEntry(POTION_ITEM, 1, 1).hasPotion());
    }

    @Test
    void withPotion_setsAndClears() {
        ContainerContentsEntry e = new ContainerContentsEntry(POTION_ITEM, 3, 2).withPotion(HEALING);
        assertEquals(HEALING, e.potionId());
        assertTrue(e.hasPotion());
        assertEquals(3, e.count());
        assertEquals(2, e.weight());
        assertNull(e.withPotion(null).potionId());
    }

    @Test
    void scaleWithDistance_defaultsOnAndSurvivesCopies() {
        ContainerContentsEntry e = new ContainerContentsEntry(POTION_ITEM, 1, 1);
        assertTrue(e.scaleWithDistance());
        ContainerContentsEntry off = e.withScaleWithDistance(false);
        assertFalse(off.scaleWithDistance());
        assertFalse(off.withWeight(3).scaleWithDistance());
        assertFalse(off.withCount(3).scaleWithDistance());
        assertFalse(off.withPotion(HEALING).scaleWithDistance());
        assertFalse(off.cycleSlotOverride().scaleWithDistance());
        assertTrue(off.withScaleWithDistance(true).scaleWithDistance());
    }

    @Test
    void withCopies_carryPotionThrough() {
        ContainerContentsEntry e = new ContainerContentsEntry(POTION_ITEM, 1, 1).withPotion(HEALING);
        assertEquals(HEALING, e.withWeight(9).potionId());
        assertEquals(HEALING, e.withCount(4).potionId());
        assertEquals(HEALING, e.withRandomDurability(false).potionId());
        assertEquals(HEALING, e.withDurabilityChance(5).potionId());
        assertEquals(HEALING, e.withRandomEnchantment(false).potionId());
        assertEquals(HEALING, e.withEnchantmentChance(5).potionId());
        assertEquals(HEALING, e.withSlotOverride(ContainerContentsEntry.SLOT_FUEL).potionId());
        assertEquals(HEALING, e.cycleSlotOverride().potionId());
    }
}
