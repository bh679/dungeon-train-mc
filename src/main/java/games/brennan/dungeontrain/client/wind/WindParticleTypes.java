package games.brennan.dungeontrain.client.wind;

import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Which particles the train wind blows, and how much ({@link AmbientWind}): an allow-list of ambient particles —
 * weather splashes, falling drips, snow, ash, spores, smoke, petals — weighted by how light they are (1 = blown
 * at the full drift speed, 0 = never). Combat, potion and block-break particles are left alone, and so are
 * Windy's streaks and DT's own fallback streaks ({@code cloud}), which are aimed by the wind already.
 *
 * <p>Modded particles join by name ({@link #KEYWORDS}), so another mod's falling leaves blow too.</p>
 */
public final class WindParticleTypes {

    static final float LIGHT = 1.0f;
    static final float SMOKE = 0.8f;
    static final float HEAVY = 0.5f;
    static final float SPLASH = 0.3f;
    /** Weight for a modded particle whose id names something light. */
    static final float MODDED = 0.8f;

    private static final Map<String, Float> VANILLA = Map.ofEntries(
            Map.entry("snowflake", LIGHT),
            Map.entry("white_ash", LIGHT),
            Map.entry("ash", LIGHT),
            Map.entry("crimson_spore", LIGHT),
            Map.entry("warped_spore", LIGHT),
            Map.entry("spore_blossom_air", LIGHT),
            Map.entry("cherry_leaves", LIGHT),
            Map.entry("mycelium", LIGHT),
            Map.entry("smoke", SMOKE),
            Map.entry("white_smoke", SMOKE),
            Map.entry("campfire_cosy_smoke", SMOKE),
            Map.entry("campfire_signal_smoke", SMOKE),
            Map.entry("dust_plume", SMOKE),
            Map.entry("large_smoke", HEAVY),
            Map.entry("falling_water", HEAVY),
            Map.entry("falling_lava", HEAVY),
            Map.entry("falling_honey", HEAVY),
            Map.entry("falling_spore_blossom", HEAVY),
            Map.entry("falling_dust", HEAVY),
            Map.entry("rain", SPLASH));

    /** Words in a modded particle's path that mark it as light enough to blow. */
    private static final String[] KEYWORDS = {"leaf", "leaves", "petal", "ash", "spore", "snow", "smoke", "pollen"};
    /** Namespaces whose particles the wind never moves: Windy places and aims its own streaks. */
    private static final String[] EXCLUDED_NAMESPACES = {"windy"};

    private static final Map<ParticleType<?>, Float> CACHE = new IdentityHashMap<>();

    private WindParticleTypes() {}

    /** Wind weight for a particle type (0 = not blown). Render thread only. */
    public static float weight(ParticleType<?> type) {
        Float cached = CACHE.get(type);
        if (cached != null) return cached;
        ResourceLocation id = BuiltInRegistries.PARTICLE_TYPE.getKey(type);
        float w = id == null ? 0.0f : weightFor(id.getNamespace(), id.getPath());
        CACHE.put(type, w);
        return w;
    }

    /** The rule itself, by id: vanilla allow-list, then keyword match for other mods. */
    static float weightFor(String namespace, String path) {
        if ("minecraft".equals(namespace)) {
            return VANILLA.getOrDefault(path, 0.0f);
        }
        for (String ns : EXCLUDED_NAMESPACES) {
            if (ns.equals(namespace)) return 0.0f;
        }
        // Drips hanging under a block stay put until they fall.
        if (path.startsWith("dripping_")) return 0.0f;
        for (String k : KEYWORDS) {
            if (path.contains(k)) return MODDED;
        }
        return 0.0f;
    }
}
