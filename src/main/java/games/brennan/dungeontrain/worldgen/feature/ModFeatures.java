package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Mod-side worldgen-feature registry. Mirrors the
 * {@link games.brennan.dungeontrain.registry.ModItems} pattern: a
 * {@link DeferredRegister} attached to the mod-event bus from the mod
 * constructor.
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

    public static final DeferredRegister<Feature<?>> FEATURES = DeferredRegister.create(
        ForgeRegistries.FEATURES, DungeonTrain.MOD_ID);

    public static final RegistryObject<Feature<NoneFeatureConfiguration>> TRACK_BED = FEATURES.register(
        "track_bed",
        TrackBedFeature::new
    );

    private ModFeatures() {}

    /** Call from the mod constructor to attach the {@link DeferredRegister} to the mod-event bus. */
    public static void register(IEventBus modBus) {
        FEATURES.register(modBus);
    }
}
