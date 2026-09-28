package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Which {@code minecraft:} worldgen files William Wythers' Overhauled Overworld ships — the exact
 * scope of what {@link VanillaBiomeFeatures} and {@link VanillaBiomeTwins} confine.
 *
 * <p>Read from the WWOO mod jar itself ({@code resources/<pack>/data/minecraft/worldgen/…}), not by
 * "differs from vanilla": other mods rewrite vanilla biomes too — BetterNether patches the vanilla
 * Nether biomes and BetterEnd the End ones through WorldWeaver — and those edits must stay
 * everywhere. Only WWOO's are stretch-bound. (One exception: the vanilla-style Nether band's core drops
 * other mods' features from vanilla Nether biomes — see {@code NetherCoreFeatureFilter}.)</p>
 *
 * <p>WWOO's {@code minecraft:} tag edits are global and can't be stretch-bound; the ones its
 * features don't need are dropped at load by {@link WwooTagFilter}.</p>
 */
public final class WwooDatapack {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "wwoo";

    private static volatile WwooDatapack cached;

    private final Set<ResourceKey<Biome>> biomes;
    private final Set<ResourceKey<PlacedFeature>> placedFeatures;
    private final Set<ResourceLocation> configuredFeatures;

    private WwooDatapack(Set<ResourceKey<Biome>> biomes, Set<ResourceKey<PlacedFeature>> placedFeatures,
                         Set<ResourceLocation> configuredFeatures) {
        this.biomes = biomes;
        this.placedFeatures = placedFeatures;
        this.configuredFeatures = configuredFeatures;
    }

    /** The scan, done once per JVM; empty sets when WWOO isn't loaded or the jar can't be read. */
    public static WwooDatapack get() {
        WwooDatapack v = cached;
        if (v == null) {
            v = scan();
            cached = v;
        }
        return v;
    }

    /** {@code minecraft:} biomes WWOO rewrites. */
    public Set<ResourceKey<Biome>> biomes() {
        return biomes;
    }

    /** {@code minecraft:} placed features WWOO overrides. */
    public Set<ResourceKey<PlacedFeature>> placedFeatures() {
        return placedFeatures;
    }

    /** {@code minecraft:} configured features WWOO overrides (ids). */
    public Set<ResourceLocation> configuredFeatures() {
        return configuredFeatures;
    }

    public boolean isEmpty() {
        return biomes.isEmpty() && placedFeatures.isEmpty() && configuredFeatures.isEmpty();
    }

    private static WwooDatapack scan() {
        Set<ResourceKey<Biome>> biomes = new HashSet<>();
        Set<ResourceKey<PlacedFeature>> placed = new HashSet<>();
        Set<ResourceLocation> configured = new HashSet<>();
        try {
            IModFileInfo info = ModList.get().getModFileById(MOD_ID);
            if (info == null) return new WwooDatapack(Set.of(), Set.of(), Set.of());
            Path root = info.getFile().findResource("resources");
            if (!Files.isDirectory(root)) return new WwooDatapack(Set.of(), Set.of(), Set.of());
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    // <pack>/data/minecraft/worldgen/<kind>/<path...>.json
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    String[] parts = rel.split("/", 5);
                    if (parts.length < 5 || !"data".equals(parts[1]) || !"minecraft".equals(parts[2])
                            || !parts[3].equals("worldgen") || !rel.endsWith(".json")) return;
                    String[] kindAndPath = parts[4].split("/", 2);
                    if (kindAndPath.length < 2) return;
                    ResourceLocation id = ResourceLocation.withDefaultNamespace(
                            kindAndPath[1].substring(0, kindAndPath[1].length() - ".json".length()));
                    switch (kindAndPath[0]) {
                        case "biome" -> biomes.add(ResourceKey.create(Registries.BIOME, id));
                        case "placed_feature" -> placed.add(ResourceKey.create(Registries.PLACED_FEATURE, id));
                        case "configured_feature" -> configured.add(id);
                        default -> { }
                    }
                });
            }
            LOGGER.info("[DungeonTrain] WWOO datapack scope: {} biomes, {} placed features, {} configured features",
                    biomes.size(), placed.size(), configured.size());
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Could not read WWOO's datapack; its confinement is off this session", t);
            return new WwooDatapack(Set.of(), Set.of(), Set.of());
        }
        return new WwooDatapack(Set.copyOf(biomes), Set.copyOf(placed), Set.copyOf(configured));
    }
}
