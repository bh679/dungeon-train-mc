package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.density.NetherCoreBiomes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The floor skin for Biomes O' Plenty Nether biomes in the core — the BoP counterpart of
 * {@link BetterNetherSurfaceSkins}, which {@link NetherSurfacePalette} delegates to for the
 * {@code biomesoplenty} namespace.
 *
 * <p>Mirrors BoP's own Nether surface rule ({@code BOPSurfaceRuleData#makeBOPNetherRules}). Only
 * erupting_inferno is skinned: its brimstone floor is what its brimstone buds and clusters need
 * ({@code #biomesoplenty:brimstone_decoration_placeable} = netherrack, brimstone). On a plain netherrack
 * floor a neighbouring chunk's netherrack ores ({@code ore_blackstone}, {@code ore_magma},
 * {@code ore_gravel_nether}) and basalt-delta blobs rewrite the block under a standing bud, which then
 * can't survive; none of them target brimstone. Blocks are named by id, so DT has no compile dependency
 * on BoP; an id that doesn't resolve falls back to netherrack.</p>
 */
final class BopNetherSurfaceSkins {

    /** Keyed by BoP biome path → the block for every skin depth. */
    private static final Map<String, String> SKINS = Map.of(
            "erupting_inferno", "biomesoplenty:brimstone");

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final Map<String, BlockState> RESOLVED = new ConcurrentHashMap<>();

    private BopNetherSurfaceSkins() {}

    /** True for a BoP biome with a skin. */
    static boolean hasSurface(ResourceKey<Biome> biome) {
        return skinId(biome) != null;
    }

    /** Surface block for a BoP biome at any skin depth; netherrack otherwise. */
    static BlockState surfaceBlock(ResourceKey<Biome> biome) {
        String id = skinId(biome);
        return id == null ? NETHERRACK : block(id);
    }

    /** The skin block id for a BoP biome, or {@code null} when it keeps plain netherrack. */
    static String skinId(ResourceKey<Biome> biome) {
        if (biome == null) return null;
        ResourceLocation id = biome.location();
        if (!NetherCoreBiomes.BOP_NAMESPACE.equals(id.getNamespace())) return null;
        return SKINS.get(id.getPath());
    }

    private static BlockState block(String id) {
        return RESOLVED.computeIfAbsent(id, key -> BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse(key))
                .map(b -> b.defaultBlockState())
                .orElse(NETHERRACK));
    }
}
