package games.brennan.dungeontrain.ship.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class SavedPlotBiomeTest {

    private static CompoundTag withPlotBiome(String biome) {
        CompoundTag plot = new CompoundTag();
        plot.putString("biome", biome);
        CompoundTag tag = new CompoundTag();
        tag.put("plot", plot);
        return tag;
    }

    @Test
    void savedBiomeIsRestored() {
        assertTrue(SavedPlotBiome.restoredFromSave(withPlotBiome("minecraft:plains")));
    }

    @Test
    void plotWithoutBiomeIsNotRestored() {
        CompoundTag tag = new CompoundTag();
        tag.put("plot", new CompoundTag());
        assertFalse(SavedPlotBiome.restoredFromSave(tag));
    }

    @Test
    void missingPlotIsNotRestored() {
        assertFalse(SavedPlotBiome.restoredFromSave(new CompoundTag()));
    }

    @Test
    void unparseableBiomeIsNotRestored() {
        // Sable's load drops a location that doesn't parse, so the read in createSubLevel still counts.
        assertFalse(SavedPlotBiome.restoredFromSave(withPlotBiome("Not A Location!")));
    }
}
