package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Drops the advancement tabs of bundled third-party mods that aren't part of Dungeon Train's
 * progression — BetterNether and BetterEnd — from the raw datapack map before vanilla parses it.
 *
 * <p>An advancement absent from the map is never registered, never sent to a client, cannot be
 * earned and never toasts, so the whole tab simply doesn't exist. Their criterion triggers keep
 * firing but find no listeners. Each mod's {@code recipes/} advancements are kept: they are hidden,
 * tab-less, and are what unlock the mod's recipes in the recipe book.</p>
 *
 * <p>Pure function over the map, returning a new map in the same order; the input is never
 * mutated, and it is returned as-is when nothing matched.</p>
 */
public final class ForeignAdvancementFilter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Namespaces whose display advancements are removed. */
    public static final Set<String> BLOCKED_NAMESPACES = Set.of("betternether", "betterend");

    /** Paths under this prefix survive — recipe-unlock advancements, not tab entries. */
    public static final String KEPT_PATH_PREFIX = "recipes/";

    private ForeignAdvancementFilter() {}

    /** Whether {@code id} is a blocked mod's display advancement. */
    public static boolean isBlocked(ResourceLocation id) {
        return BLOCKED_NAMESPACES.contains(id.getNamespace())
            && !id.getPath().startsWith(KEPT_PATH_PREFIX);
    }

    public static Map<ResourceLocation, JsonElement> removeBlocked(Map<ResourceLocation, JsonElement> loaded) {
        Map<String, Integer> removedPerNamespace = new TreeMap<>();
        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>(loaded.size());
        for (Map.Entry<ResourceLocation, JsonElement> e : loaded.entrySet()) {
            if (isBlocked(e.getKey())) {
                removedPerNamespace.merge(e.getKey().getNamespace(), 1, Integer::sum);
                continue;
            }
            out.put(e.getKey(), e.getValue());
        }
        if (removedPerNamespace.isEmpty()) return loaded;
        LOGGER.info("[DungeonTrain] Removed third-party advancement tabs: {}", removedPerNamespace);
        return out;
    }
}
