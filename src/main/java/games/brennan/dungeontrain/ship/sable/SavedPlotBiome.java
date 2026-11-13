package games.brennan.dungeontrain.ship.sable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * Whether a saved sub-level will get its plot biome back from its own save. Sable's
 * {@code ServerLevelPlot.load} restores {@code plot.biome} when the tag holds a parseable
 * location, and only then, so this mirrors that test exactly. See
 * {@code ServerSubLevelContainerReloadBiomeMixin} for what the answer is used for.
 */
public final class SavedPlotBiome {

    private SavedPlotBiome() {}

    /** @param subLevelTag the sub-level's full saved tag ({@code SubLevelData.fullTag()}) */
    public static boolean restoredFromSave(CompoundTag subLevelTag) {
        CompoundTag plot = subLevelTag.getCompound("plot");
        return plot.contains("biome") && ResourceLocation.tryParse(plot.getString("biome")) != null;
    }
}
