package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The sorted list of every <b>vanilla</b> structure-piece template id ({@code minecraft:village/...},
 * {@code minecraft:bastion/...}, {@code minecraft:end_city/...}, …) the running server can load through
 * its {@code StructureTemplateManager}. The stacks band ({@link StacksBand}) picks one per tower from
 * this list, so the catalogue must be identical on every worldgen worker and across reloads: it is
 * built once per server from {@code StructureTemplateManager#listTemplates()} (which walks
 * {@code data/<ns>/structure/**.nbt} across all loaded resource packs), filtered to the
 * {@code minecraft} namespace, and sorted by string. Another mod adding {@code minecraft:}-namespaced
 * templates would shift the picks — a datapack-dependent layout, like every vanilla structure.
 *
 * <p>Only ids are catalogued (~1180 strings). Templates themselves are loaded lazily per pick by the
 * manager, which memoises them in its own concurrent repository — there is no second template cache.</p>
 *
 * <p>{@link #EXCLUDED_PREFIXES} drops piece families that read wrong as floating towers: trial
 * chambers are built around vaults + trial spawners, and stacking forty of those mid-air is a
 * gameplay decision, not a build-height one. One-line constant, easy to revisit.</p>
 */
public final class VanillaTemplateCatalogue {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Template-path prefixes (after the namespace) excluded from the catalogue. */
    static final Set<String> EXCLUDED_PREFIXES = Set.of("trial_chambers/");

    private static volatile MinecraftServer cachedServer;
    private static volatile List<ResourceLocation> cachedIds = List.of();

    private VanillaTemplateCatalogue() {}

    /**
     * The catalogue for {@code server} — built on first use, memoised until a different server instance
     * asks (an integrated-server restart, say). Never null; empty when the manager lists nothing (the
     * feature then places no tower).
     */
    public static List<ResourceLocation> ids(MinecraftServer server) {
        if (cachedServer == server) return cachedIds;
        synchronized (VanillaTemplateCatalogue.class) {
            if (cachedServer == server) return cachedIds;
            List<ResourceLocation> built = build(server);
            cachedIds = built;
            cachedServer = server;
            return built;
        }
    }

    private static List<ResourceLocation> build(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return List.of();
            List<ResourceLocation> ids = overworld.getStructureManager().listTemplates()
                    .filter(id -> ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace()))
                    .filter(id -> !excluded(id.getPath()))
                    .sorted(Comparator.comparing(ResourceLocation::toString))
                    .toList();
            LOGGER.info("[DungeonTrain] stacks band: catalogued {} vanilla structure templates", ids.size());
            return ids;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] stacks band: failed to list vanilla structure templates", t);
            return List.of();
        }
    }

    /** Pure exclusion test on the template path (no namespace). */
    static boolean excluded(String path) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Pure, seed-stable pick from {@code ids} for the stack in chunk {@code (chunkX, chunkZ)}, salted by
     * {@code attempt} so a template that does not fit the chunk rerolls deterministically. Null when the
     * catalogue is empty.
     */
    public static ResourceLocation pick(List<ResourceLocation> ids, long seed, int chunkX, int chunkZ, int attempt) {
        int idx = StacksBand.pickIndex(seed, chunkX, chunkZ, attempt, ids.size());
        return idx < 0 ? null : ids.get(idx);
    }
}
