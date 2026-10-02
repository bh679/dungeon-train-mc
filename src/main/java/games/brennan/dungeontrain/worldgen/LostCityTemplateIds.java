package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.JigsawStructureAccessor;
import games.brennan.dungeontrain.mixin.ListPoolElementAccessor;
import games.brennan.dungeontrain.mixin.SinglePoolElementAccessor;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The Big Lost City templates a Lost City structure can actually place: every template in the
 * {@link LostCityStructures#NAMESPACE} namespace reachable from the start pool of a structure DT lets start
 * (the mod's own and DT's trackside copies — not Lost City Terrain Fit's all-biome copies), following each
 * pool's fallback. The mod ships 75 templates but its pools name only 42; the rest (pre-loot drafts, the
 * unwired warship and parking garage) never generate, so {@code LostCityTemplatePreloadEvents} leaves them on disk.
 */
public final class LostCityTemplateIds {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LostCityTemplateIds() {}

    /** A pool as the walk sees it: the templates its elements place, and its fallback ({@code null} for none). */
    public record PoolView(List<ResourceLocation> templates, ResourceLocation fallback) {}

    private record Resolved(Registry<Structure> structures, Registry<StructureTemplatePool> pools,
                            List<ResourceLocation> ids) {}

    private static volatile Resolved resolved;

    /**
     * The {@link LostCityStructures#NAMESPACE} templates reachable from {@code startPools}, each pool read by
     * {@code pools} ({@code null} for an unknown pool), fallbacks followed, each pool visited once.
     */
    public static Set<ResourceLocation> collect(Collection<ResourceLocation> startPools,
                                                Function<ResourceLocation, PoolView> pools) {
        Set<ResourceLocation> found = new TreeSet<>();
        Set<ResourceLocation> seen = new HashSet<>();
        Deque<ResourceLocation> queue = new ArrayDeque<>(startPools);
        while (!queue.isEmpty()) {
            ResourceLocation id = queue.poll();
            if (!seen.add(id)) continue;
            PoolView pool = pools.apply(id);
            if (pool == null) continue;
            for (ResourceLocation template : pool.templates()) {
                if (LostCityStructures.NAMESPACE.equals(template.getNamespace())) found.add(template);
            }
            if (pool.fallback() != null) queue.add(pool.fallback());
        }
        return Set.copyOf(found);
    }

    /** Whether DT ever lets structure {@code id} start — a Lost City structure that isn't a Terrain Fit copy. */
    public static boolean startable(ResourceLocation id) {
        return LostCityStructures.isLostCityStructure(id) && !LostCityStructures.isTerrainFitCopy(id);
    }

    /**
     * {@link #collect} over {@code access}'s registries, sorted; memoised while the structure and pool
     * registries are the same objects (a datapack reload replaces them). Empty on failure, which is not kept.
     */
    public static List<ResourceLocation> placeable(RegistryAccess access) {
        try {
            Registry<Structure> structures = access.registryOrThrow(Registries.STRUCTURE);
            Registry<StructureTemplatePool> pools = access.registryOrThrow(Registries.TEMPLATE_POOL);
            Resolved last = resolved;
            if (last != null && last.structures() == structures && last.pools() == pools) return last.ids();
            List<ResourceLocation> ids = List.copyOf(new TreeSet<>(collect(startPools(structures), id -> view(pools, id))));
            resolved = new Resolved(structures, pools, ids);
            return ids;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Lost City template ids: failed to walk the structure registry", t);
            return List.of();
        }
    }

    private static List<ResourceLocation> startPools(Registry<Structure> structures) {
        List<ResourceLocation> starts = new ArrayList<>();
        for (Map.Entry<ResourceKey<Structure>, Structure> e : structures.entrySet()) {
            if (!startable(e.getKey().location())) continue;
            if (!(e.getValue() instanceof JigsawStructure jigsaw)) continue;
            ((JigsawStructureAccessor) (Object) jigsaw).dungeontrain$startPool().unwrapKey()
                    .ifPresent(key -> starts.add(key.location()));
        }
        return starts;
    }

    private static PoolView view(Registry<StructureTemplatePool> pools, ResourceLocation id) {
        StructureTemplatePool pool = pools.get(id);
        if (pool == null) return null;
        List<ResourceLocation> templates = new ArrayList<>();
        // weighted elements repeat in the shuffle; the walk's set folds them
        for (StructurePoolElement element : pool.getShuffledTemplates(RandomSource.create(0L))) addTemplates(element, templates);
        ResourceLocation fallback = pool.getFallback().unwrapKey().map(ResourceKey::location).orElse(null);
        return new PoolView(templates, fallback);
    }

    private static void addTemplates(StructurePoolElement element, List<ResourceLocation> out) {
        if (element instanceof SinglePoolElement single) {
            ((SinglePoolElementAccessor) single).dungeontrain$template().left().ifPresent(out::add);
        } else if (element instanceof ListPoolElement list) {
            for (StructurePoolElement part : ((ListPoolElementAccessor) list).dungeontrain$elements()) addTemplates(part, out);
        }
    }
}
