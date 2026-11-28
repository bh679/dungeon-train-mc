package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.perks.effects.hoarder.ChestSenseRarity.Tier;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Chest Sense's rarity bands (Brennan, 2026-11-28); each band runs up to its top bound. */
final class ChestSenseRarityTest {

    @Test
    @DisplayName("each band runs up to its top bound, gaps closing upward")
    void bandEdges() {
        assertEquals(Tier.GREEN, ChestSenseRarity.tierFor(0.0));
        assertEquals(Tier.GREEN, ChestSenseRarity.tierFor(0.2));
        assertEquals(Tier.PURPLE, ChestSenseRarity.tierFor(0.21));
        assertEquals(Tier.PURPLE, ChestSenseRarity.tierFor(0.5));
        assertEquals(Tier.PURPLE, ChestSenseRarity.tierFor(1.0));
        assertEquals(Tier.LIGHT_BLUE, ChestSenseRarity.tierFor(1.5));
        assertEquals(Tier.LIGHT_BLUE, ChestSenseRarity.tierFor(4.0));
        assertEquals(Tier.YELLOW, ChestSenseRarity.tierFor(4.5));
        assertEquals(Tier.YELLOW, ChestSenseRarity.tierFor(10.0));
        assertEquals(Tier.IRON, ChestSenseRarity.tierFor(10.5));
        assertEquals(Tier.IRON, ChestSenseRarity.tierFor(20.0));
        assertEquals(Tier.STONE, ChestSenseRarity.tierFor(21.0));
        assertEquals(Tier.STONE, ChestSenseRarity.tierFor(50.0));
        assertEquals(Tier.WOOD, ChestSenseRarity.tierFor(50.5));
        assertEquals(Tier.WOOD, ChestSenseRarity.tierFor(100.0));
    }

    @Test
    @DisplayName("a chance stored as a float keeps its band at the edges")
    void floatEdges() {
        assertEquals(Tier.GREEN, ChestSenseRarity.tierFor(0.2F));
        assertEquals(Tier.PURPLE, ChestSenseRarity.tierFor(1.0F));
        assertEquals(Tier.YELLOW, ChestSenseRarity.tierFor(10.0F));
    }

    @Test
    @DisplayName("no chance is unknown")
    void unknown() {
        assertEquals(Tier.UNKNOWN, ChestSenseRarity.tierFor(Double.NaN));
        assertEquals(Tier.UNKNOWN, ChestSenseRarity.tierFor(-1));
        assertEquals(Double.NaN, ChestSenseRarity.pctOf(1, 0));
        assertEquals(Tier.UNKNOWN, Tier.byOrdinal(99));
        assertEquals(Tier.UNKNOWN, Tier.byOrdinal(-1));
    }

    @Test
    @DisplayName("weight over total weight, in percent")
    void pct() {
        assertEquals(0.5, ChestSenseRarity.pctOf(1, 200), 1e-9);
        assertEquals(Tier.GREEN, ChestSenseRarity.tierFor(ChestSenseRarity.pctOf(1, 500)));
    }

    @Test
    @DisplayName("the stamp lands in NeoForgeData beside other keys, leaving the input untouched")
    void stamp() {
        CompoundTag in = new CompoundTag();
        CompoundTag neo = new CompoundTag();
        neo.putInt("dt_rolled_items", 7);
        in.put("NeoForgeData", neo);
        CompoundTag out = ChestSenseRarity.withRarestPct(in, 3.0);
        assertEquals(3.0F, out.getCompound("NeoForgeData").getFloat(ChestSenseRarity.NBT_KEY));
        assertEquals(7, out.getCompound("NeoForgeData").getInt("dt_rolled_items"));
        assertEquals(false, in.getCompound("NeoForgeData").contains(ChestSenseRarity.NBT_KEY));
        assertSame(in, ChestSenseRarity.withRarestPct(in, Double.NaN));
    }
}
