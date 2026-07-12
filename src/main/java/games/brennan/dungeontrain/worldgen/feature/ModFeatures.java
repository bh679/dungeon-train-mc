package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.platform.DtRegistrar;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

import java.util.function.Supplier;

/**
 * Mod-side worldgen-feature registry. Registered via {@link DtRegistrar}
 * (loader-neutral) instead of a direct {@code DeferredRegister} — see
 * {@link games.brennan.dungeontrain.advancement.ModAdvancementTriggers} for
 * the pattern and the root attach timing.
 *
 * <p>Registers {@link TrackBedFeature} under id {@code dungeontrain:track_bed},
 * which the datapack JSONs in
 * {@code data/dungeontrain/worldgen/configured_feature/track_bed.json} and
 * {@code .../placed_feature/track_bed.json} reference. Three Forge biome
 * modifiers under {@code data/dungeontrain/forge/biome_modifier/} attach
 * the placed feature to overworld / nether / end biomes — only the modifier
 * matching the world's chosen starting dimension ever fires (chunks only
 * generate against their dimension's biome registry).</p>
 */
public final class ModFeatures {

    public static final Supplier<Feature<NoneFeatureConfiguration>> TRACK_BED =
        register("track_bed", TrackBedFeature::new);

    public static final Supplier<Feature<NoneFeatureConfiguration>> DISINTEGRATION =
        register("disintegration", DisintegrationFeature::new);

    public static final Supplier<Feature<NoneFeatureConfiguration>> NETHER_TRANSITION =
        register("nether_transition", NetherTransitionFeature::new);

    private ModFeatures() {}

    private static <I extends Feature<?>> Supplier<I> register(String name, Supplier<I> factory) {
        return DtRegistrar.get().register(Registries.FEATURE, name, factory);
    }

    /** Call from the mod constructor to force this class's static fields (and their registrations) to run. */
    public static void init() {}
}
