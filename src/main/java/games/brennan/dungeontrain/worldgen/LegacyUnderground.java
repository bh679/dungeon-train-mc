package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.worldgen.legacy.LegacyBands;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Set;

/**
 * The underground set that never generates in the legacy bands or the {@link SunkZone}: vanilla things
 * pinned to absolute depths, which on legacy terrain (reshaped, or sunk 80 blocks) surface in the open
 * — geodes hanging over valleys, ancient cities and trial chambers in the band's floor.
 *
 * <p>The old-generator eras already skip all vanilla decoration and structure pieces; this is what
 * reaches the modern-preset bands (Amplified, Large Biomes) and the sunk approach, which keep the rest
 * of vanilla's decoration. Strongholds are deliberately not on the list — the End portal and the
 * stronghold-ring logic depend on them.</p>
 */
public final class LegacyUnderground {

    private LegacyUnderground() {}

    /** Structures (by registry id) left out of legacy and sunk chunks. */
    static final Set<String> STRUCTURES = Set.of(
        "minecraft:ancient_city",
        "minecraft:trial_chambers",
        "minecraft:mineshaft",
        "minecraft:mineshaft_mesa");

    /** Placed features (by registry id) left out of legacy and sunk chunks. */
    static final Set<String> FEATURES = Set.of(
        "minecraft:amethyst_geode",
        "minecraft:monster_room",
        "minecraft:monster_room_deep",
        "minecraft:fossil_upper",
        "minecraft:fossil_lower");

    /** Whether chunk {@code (chunkX, chunkZ)} belongs to a legacy band or the sunk zone. */
    public static boolean appliesTo(ServerLevel level, int chunkX, int chunkZ) {
        return LegacyBands.kindOfChunk(level, chunkX, chunkZ) != null
            || SunkZone.isSunkChunk(level, chunkX, chunkZ);
    }

    public static boolean excludesStructure(ResourceLocation id) {
        return id != null && STRUCTURES.contains(id.toString());
    }

    public static boolean excludesFeature(ResourceLocation id) {
        return id != null && FEATURES.contains(id.toString());
    }
}
