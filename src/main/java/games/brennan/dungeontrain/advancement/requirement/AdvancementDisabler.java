package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drops the relay's {@link AdvancementFlag#DISABLED disabled} advancements from the raw datapack
 * map before vanilla parses it, and re-parents their children so the tree stays connected.
 *
 * <p>Removal is the whole mechanism: an advancement absent from the registry cannot be earned,
 * is never sent to a client, and is not a prerequisite of the capstone (which walks the registry).
 * Vanilla would otherwise orphan the children of a missing parent — {@code AdvancementTree}
 * logs "Couldn't load N advancement(s), parents not found" and drops them from the tree while the
 * registry still holds them — so every child whose parent was removed is re-pointed at the
 * nearest surviving ancestor. A disabled chain collapses onto the first enabled ancestor.</p>
 *
 * <p>Pure function over Gson trees, returning a new map in the same order; the input is never
 * mutated. Only ids {@link RequirementJsonRewriter#isOurs ours} are ever removed — the relay
 * allowlists them too, and the root is refused there, so a whole tab can never vanish.</p>
 */
public final class AdvancementDisabler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private AdvancementDisabler() {}

    /**
     * @param loaded   the datapack map (after the requirement rewrite)
     * @param disabled ids the relay has disabled
     * @param modId    the namespace whose advancements are ours
     * @return a new map without the disabled advancements, children re-parented
     */
    public static Map<ResourceLocation, JsonElement> removeAll(Map<ResourceLocation, JsonElement> loaded,
                                                               Set<ResourceLocation> disabled,
                                                               String modId) {
        if (disabled == null || disabled.isEmpty()) return loaded;
        // parent of each removed advancement, so a child can climb past it.
        Map<ResourceLocation, ResourceLocation> removedParent = new HashMap<>();
        for (ResourceLocation id : disabled) {
            if (!RequirementJsonRewriter.isOurs(id, modId)) continue;
            JsonElement el = loaded.get(id);
            if (el == null || !el.isJsonObject()) continue; // not in this datapack — nothing to drop
            removedParent.put(id, parentOf(el.getAsJsonObject()));
        }
        if (removedParent.isEmpty()) return loaded;

        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>(loaded.size());
        List<String> reparented = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : loaded.entrySet()) {
            ResourceLocation id = e.getKey();
            if (removedParent.containsKey(id)) continue;
            JsonElement value = e.getValue();
            if (value == null || !value.isJsonObject()) {
                out.put(id, value);
                continue;
            }
            ResourceLocation parent = parentOf(value.getAsJsonObject());
            if (parent == null || !removedParent.containsKey(parent)) {
                out.put(id, value);
                continue;
            }
            ResourceLocation survivor = nearestSurvivor(parent, removedParent);
            if (survivor == null) {
                // Every ancestor is gone (the relay refuses the root, so this is a malformed
                // datapack rather than an operator's edit). Leave the child as vanilla found it.
                out.put(id, value);
                continue;
            }
            JsonObject copy = value.getAsJsonObject().deepCopy();
            copy.add("parent", new JsonPrimitive(survivor.toString()));
            out.put(id, copy);
            reparented.add(shortName(id) + "→" + shortName(survivor));
        }
        // One line per datapack load: the operator's proof that the relay's switch reached here.
        LOGGER.info("[DungeonTrain] Advancement flags: {} disabled by relay: {}{}",
            removedParent.size(),
            removedParent.keySet().stream().map(AdvancementDisabler::shortName).sorted().toList(),
            reparented.isEmpty() ? "" : " (re-parented " + String.join(", ", reparented) + ")");
        return out;
    }

    /** Climb {@code from} through removed advancements to the first one that survives, or null. */
    private static ResourceLocation nearestSurvivor(ResourceLocation from,
                                                    Map<ResourceLocation, ResourceLocation> removedParent) {
        ResourceLocation at = from;
        int hops = 0;
        while (at != null && removedParent.containsKey(at)) {
            at = removedParent.get(at);
            if (++hops > removedParent.size()) return null; // a cycle in the data; give up
        }
        return at;
    }

    private static ResourceLocation parentOf(JsonObject advancement) {
        JsonElement p = advancement.get("parent");
        if (p == null || !p.isJsonPrimitive() || !p.getAsJsonPrimitive().isString()) return null;
        return ResourceLocation.tryParse(p.getAsString());
    }

    private static String shortName(ResourceLocation id) {
        String path = id.getPath();
        return path.startsWith(RequirementJsonRewriter.PATH_PREFIX)
            ? path.substring(RequirementJsonRewriter.PATH_PREFIX.length()) : id.toString();
    }
}
