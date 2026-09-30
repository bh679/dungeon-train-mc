package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.biome.BiomeData;
import net.minecraft.data.worldgen.Carvers;
import net.minecraft.data.worldgen.ProcessorLists;
import net.minecraft.data.worldgen.features.FeatureUtils;
import net.minecraft.data.worldgen.placement.PlacementUtils;
import org.slf4j.Logger;

/**
 * Vanilla's biomes, placed/configured features and carvers rebuilt from code — the reference the WWOO
 * confinement compares the live (datapack-rewritten) registries against. Datapacks can't touch it.
 *
 * <p>A subset of {@code VanillaRegistries.createLookup()} on purpose: the full builder also bootstraps
 * the multi-noise biome-source parameter lists, which WorldWeaver (BCLib/BetterNether/BetterEnd)
 * patches to reference its own biomes — and those aren't in vanilla's biome bootstrap, so the full
 * build fails with "Unreferenced key … betternether:…". These five registries are self-contained.</p>
 */
public final class VanillaWorldgenLookup {

    private static final Logger LOGGER = LogUtils.getLogger();

    private VanillaWorldgenLookup() {}

    public static HolderLookup.Provider create() {
        long t0 = System.nanoTime();
        RegistrySetBuilder builder = new RegistrySetBuilder()
                .add(Registries.CONFIGURED_CARVER, Carvers::bootstrap)
                .add(Registries.PROCESSOR_LIST, ProcessorLists::bootstrap) // fossil features reference these
                .add(Registries.CONFIGURED_FEATURE, FeatureUtils::bootstrap)
                .add(Registries.PLACED_FEATURE, PlacementUtils::bootstrap)
                .add(Registries.BIOME, BiomeData::bootstrap);
        HolderLookup.Provider lookup = builder.build(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        LOGGER.debug("[DungeonTrain] Vanilla worldgen lookup built on {} ({} ms)",
                Thread.currentThread().getName(), (System.nanoTime() - t0) / 1_000_000L);
        return lookup;
    }
}
