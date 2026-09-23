package games.brennan.dungeontrain.worldgen.structure;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * Structure sets that belong to another dimension's mod and must never be placed by the <b>overworld</b>
 * generator, whatever biomes that generator lists.
 *
 * <p>BetterEnd tags every biome that is neither {@code minecraft:} nor {@code betterend:} with
 * {@code betterend:has_structure/eternal_portal} (its {@code eternal_portals} biome modification carries
 * no dimension check). On a plain overworld source that set is dropped by vanilla's own filter, but once
 * TerraBlender lists Biomes O' Plenty biomes there the set passes, and eternal portals — ruined portals
 * that teleport to the real End — would generate in the BoP stretch and in BetterNether Nether cores.
 * BetterEnd's terrain reaches the End band pre-decorated from {@code EndBandSampler}, so the overworld
 * generator has no business placing any BetterEnd structure. {@code ChunkGeneratorStructureStateMixin}
 * asks here and answers {@code false} for such a set on the overworld generator.</p>
 *
 * <p>Pure (no registry access) so the rule is unit-tested directly.</p>
 */
public final class ForeignDimensionStructureSets {

    /** Namespaces whose structure sets belong to another dimension's mod. */
    static final Set<String> BLOCKED_NAMESPACES = Set.of("betterend");

    private ForeignDimensionStructureSets() {}

    /**
     * True when every structure in the set comes from a blocked namespace. A mixed set (one that also
     * carries a vanilla or DT structure) is left to vanilla's filter; an empty list is never blocked.
     */
    public static boolean blockedOnOverworld(List<ResourceLocation> structureIds) {
        if (structureIds == null || structureIds.isEmpty()) return false;
        for (ResourceLocation id : structureIds) {
            if (id == null || !BLOCKED_NAMESPACES.contains(id.getNamespace())) return false;
        }
        return true;
    }
}
