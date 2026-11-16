package games.brennan.dungeontrain.trader;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ExtraTradeCosts}: the marker round-trips, strips cleanly, and the grid maps to the right costs. */
final class ExtraTradeCostsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final List<ItemCost> COSTS = List.of(
        new ItemCost(Items.EMERALD_BLOCK, 64), new ItemCost(Items.EMERALD_BLOCK, 46));

    @Test
    @DisplayName("extra costs round-trip through the result stack")
    void roundTrip() {
        ItemStack marked = ExtraTradeCosts.withCosts(new ItemStack(Items.NETHERITE_CHESTPLATE), COSTS);
        List<ItemCost> read = ExtraTradeCosts.read(marked);
        assertEquals(2, read.size());
        assertEquals(64, read.get(0).count());
        assertEquals(46, read.get(1).count());
        assertTrue(read.get(1).test(new ItemStack(Items.EMERALD_BLOCK)));
    }

    @Test
    @DisplayName("stripping leaves a plain item, and a plain item has no extra costs")
    void strip() {
        ItemStack marked = ExtraTradeCosts.withCosts(new ItemStack(Items.NETHERITE_CHESTPLATE), COSTS);
        ItemStack stripped = ExtraTradeCosts.strip(marked);
        assertTrue(ExtraTradeCosts.read(stripped).isEmpty());
        assertFalse(stripped.has(DataComponents.CUSTOM_DATA));
        assertTrue(ExtraTradeCosts.read(new ItemStack(Items.DIAMOND_SWORD)).isEmpty());
    }

    @Test
    @DisplayName("a trade is paid only when every extra slot holds its cost")
    void paid() {
        SimpleContainer backing = new SimpleContainer(ExtraTradeCosts.MAX_EXTRA);
        backing.setItem(0, new ItemStack(Items.EMERALD_BLOCK, 64));
        assertFalse(ExtraTradeCosts.isPaid(COSTS, backing));
        backing.setItem(1, new ItemStack(Items.EMERALD_BLOCK, 45));
        assertFalse(ExtraTradeCosts.isPaid(COSTS, backing));
        backing.setItem(1, new ItemStack(Items.EMERALD_BLOCK, 50));
        assertTrue(ExtraTradeCosts.isPaid(COSTS, backing));
        ExtraTradeCosts.take(COSTS, backing);
        assertTrue(backing.getItem(0).isEmpty());
        assertEquals(4, backing.getItem(1).getCount());
    }

    @Test
    @DisplayName("on-screen slots map past A and B, and scroll a row of four at a time")
    void gridMapping() {
        assertEquals(-1, ExtraTradeCosts.backingIndex(0, 0));
        assertEquals(-1, ExtraTradeCosts.backingIndex(0, 1));
        assertEquals(0, ExtraTradeCosts.backingIndex(0, 2));
        assertEquals(9, ExtraTradeCosts.backingIndex(0, 11));
        assertEquals(2, ExtraTradeCosts.backingIndex(1, 0));
        assertEquals(0, ExtraTradeCosts.maxScrollRow(12));
        assertEquals(1, ExtraTradeCosts.maxScrollRow(13));
        assertEquals(5, ExtraTradeCosts.maxScrollRow(32));
    }
}
