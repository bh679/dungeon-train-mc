package games.brennan.dungeontrain.worldgen;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.Set;

/**
 * The overworld biomes VanillaBackport adds to vanilla's climate table.
 *
 * <p>VanillaBackport registers them in the {@code minecraft} namespace, and its Platform library appends
 * their climate points to the end of vanilla's {@code OverworldBiomeBuilder.addBiomes} — it never replaces a
 * vanilla point. So they reach every stretch that picks from the vanilla table; DT keeps them to the vanilla
 * overworld stretches only ({@link games.brennan.dungeontrain.worldgen.density.OverworldStretchBiomes}).</p>
 */
public final class BackportBiomes {

    private BackportBiomes() {}

    public static final ResourceKey<Biome> PALE_GARDEN = key("pale_garden");
    public static final ResourceKey<Biome> SULFUR_CAVES = key("sulfur_caves");
    public static final ResourceKey<Biome> DAPPLED_FOREST = key("dappled_forest");

    /** Every VanillaBackport overworld biome — kept out of the non-vanilla stretches. */
    public static final Set<ResourceKey<Biome>> OVERWORLD = Set.of(PALE_GARDEN, SULFUR_CAVES, DAPPLED_FOREST);

    /**
     * VanillaBackport's own TerraBlender overworld region. DT never reads it — its biomes reach the vanilla
     * stretch through Platform's table — but registering it reshuffles TerraBlender's region layout, and with
     * it the Biomes O' Plenty stretch. So DT drops it ({@code mixin.terrablender.RegionsMixin}).
     */
    public static final ResourceLocation TERRABLENDER_REGION =
            ResourceLocation.fromNamespaceAndPath("vanillabackport", "overworld");

    /** True for a TerraBlender region DT keeps out of the region layout. */
    public static boolean isVetoedRegion(ResourceLocation name) {
        return TERRABLENDER_REGION.equals(name);
    }

    private static ResourceKey<Biome> key(String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(path));
    }
}
