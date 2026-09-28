package games.brennan.dungeontrain.worldgen.feature;

import games.brennan.dungeontrain.worldgen.density.BetterNetherCoreBiomes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The floor skin for BetterNether biomes in the alternate Nether bands — the BetterNether counterpart
 * of the vanilla cases in {@link NetherSurfacePalette}, which delegates here for the
 * {@code betternether} namespace.
 *
 * <p>Each entry mirrors the blocks in that biome's own WorldWeaver surface rule
 * ({@code data/betternether/wover/worldgen/surface_rules/<biome>.json}), minus the absolute-Y bedrock
 * rules that don't apply to the Y-remapped core. Blocks are named by id, so DT has no compile
 * dependency on BetterNether. An id that doesn't resolve falls back to netherrack, so a renamed block
 * degrades to plain floor rather than failing worldgen.</p>
 */
final class BetterNetherSurfaceSkins {

    /**
     * One biome's skin. {@code top} caps the exposed floor, swapped for {@code alt} where the coherent
     * noise is at least {@code altFrom}. {@code under} fills the remaining skin depth; {@code null}
     * leaves netherrack there.
     */
    private record Skin(String top, String alt, double altFrom, String under) {
        static Skin cap(String top) {
            return new Skin(top, null, 1.0, null);
        }

        static Skin mottled(String top, String alt, double altFrom) {
            return new Skin(top, alt, altFrom, null);
        }
    }

    private static final String NETHERRACK_MOSS = "betternether:netherrack_moss";
    private static final String NETHER_MYCELIUM = "betternether:nether_mycelium";
    private static final String MUSHROOM_GRASS = "betternether:mushroom_grass";
    private static final String SWAMPLAND_GRASS = "betternether:swampland_grass";
    private static final String SOUL_SANDSTONE = "betternether:soul_sandstone";

    /** Keyed by BetterNether biome path. Sub-biomes never get a core cell, so they're not listed. */
    private static final Map<String, Skin> SKINS = Map.ofEntries(
            Map.entry("bone_reef", Skin.cap(MUSHROOM_GRASS)),
            Map.entry("sulfuric_bone_reef", Skin.cap("betternether:sepia_mushroom_grass")),
            Map.entry("crimson_glowing_woods", Skin.mottled("minecraft:crimson_nylium", "minecraft:nether_wart_block", 0.8)),
            Map.entry("crimson_pinewood", Skin.mottled("minecraft:crimson_nylium", "minecraft:nether_wart_block", 0.8)),
            Map.entry("flooded_deltas", new Skin("minecraft:blackstone", "minecraft:deepslate", 0.55, "minecraft:blackstone")),
            Map.entry("gloomwood", new Skin("betternether:veined_gloomsculk", "betternether:bleached_gloomsculk", 0.7, "minecraft:sculk")),
            Map.entry("gravel_desert", new Skin("minecraft:gravel", null, 1.0, "minecraft:gravel")),
            Map.entry("magma_land", Skin.mottled("minecraft:netherrack", "minecraft:magma_block", 0.6)),
            Map.entry("nether_grasslands", new Skin(NETHERRACK_MOSS, "minecraft:soul_soil", 0.65, null)),
            Map.entry("poor_nether_grasslands", Skin.mottled(NETHERRACK_MOSS, "minecraft:soul_soil", 0.75)),
            Map.entry("nether_jungle", Skin.cap("betternether:jungle_grass")),
            Map.entry("nether_mushroom_forest", Skin.cap(NETHER_MYCELIUM)),
            Map.entry("old_fungiwoods", Skin.cap(NETHER_MYCELIUM)),
            Map.entry("nether_swampland", new Skin(SWAMPLAND_GRASS, "minecraft:soul_sand", 0.7, "minecraft:soul_soil")),
            Map.entry("old_swampland", new Skin(SWAMPLAND_GRASS, "minecraft:soul_sand", 0.7, "minecraft:soul_soil")),
            Map.entry("old_warped_woods", Skin.cap("minecraft:warped_nylium")),
            Map.entry("soul_plain", new Skin("minecraft:soul_sand", "minecraft:soul_soil", 0.5, SOUL_SANDSTONE)),
            Map.entry("upside_down_forest", Skin.mottled(NETHERRACK_MOSS, MUSHROOM_GRASS, 0.6)),
            Map.entry("wart_forest", new Skin("minecraft:soul_sand", NETHERRACK_MOSS, 0.7, SOUL_SANDSTONE)));

    private static final BlockState NETHERRACK = Blocks.NETHERRACK.defaultBlockState();
    private static final Map<String, BlockState> RESOLVED = new ConcurrentHashMap<>();

    private BetterNetherSurfaceSkins() {}

    /** True for a BetterNether biome with a skin. */
    static boolean hasSurface(ResourceKey<Biome> biome) {
        return skinFor(biome) != null;
    }

    /** Surface block at {@code depthBelowTop} (0 = exposed top) for a BetterNether biome; netherrack otherwise. */
    static BlockState surfaceBlock(ResourceKey<Biome> biome, int depthBelowTop, double noise) {
        Skin skin = skinFor(biome);
        if (skin == null) return NETHERRACK;
        if (depthBelowTop == 0) {
            return block(skin.alt() != null && noise >= skin.altFrom() ? skin.alt() : skin.top());
        }
        return skin.under() == null ? NETHERRACK : block(skin.under());
    }

    private static Skin skinFor(ResourceKey<Biome> biome) {
        if (biome == null) return null;
        ResourceLocation id = biome.location();
        if (!BetterNetherCoreBiomes.NAMESPACE.equals(id.getNamespace())) return null;
        return SKINS.get(id.getPath());
    }

    private static BlockState block(String id) {
        return RESOLVED.computeIfAbsent(id, key -> BuiltInRegistries.BLOCK
                .getOptional(ResourceLocation.parse(key))
                .map(b -> b.defaultBlockState())
                .orElse(NETHERRACK));
    }
}
