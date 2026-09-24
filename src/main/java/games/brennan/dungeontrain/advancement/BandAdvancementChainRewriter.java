package games.brennan.dungeontrain.advancement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the {@code parent} of every journey advancement in the raw datapack map so the chain runs
 * in {@link BandAdvancements#chain layout order}: each member's parent is the member before it, the
 * first hangs from {@link BandAdvancements#ANCHOR}. Members the datapack does not contain are skipped
 * over (the next present member parents to the last present one), so a missing file can never break
 * the chain. Everything else passes through untouched.
 *
 * <p>Pure functions over Gson trees returning new objects — the map {@code ServerAdvancementManager.apply}
 * hands the mixin is never mutated. Sits beside {@link games.brennan.dungeontrain.advancement.requirement.RequirementJsonRewriter}
 * in the same mixin.</p>
 */
public final class BandAdvancementChainRewriter {

    /** The tab the journey lives in. */
    public static final String PATH_PREFIX = "dungeon_train/";

    private BandAdvancementChainRewriter() {}

    /**
     * @param loaded the datapack map ({@code id → raw JSON})
     * @param chain  short advancement names in the order they should chain
     * @param anchor short name of the advancement the first member parents to
     * @param modId  our namespace
     * @return a new map in the same iteration order with the chain members' parents rewritten
     */
    public static Map<ResourceLocation, JsonElement> rewriteParents(Map<ResourceLocation, JsonElement> loaded,
                                                                    List<String> chain, String anchor, String modId) {
        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>(loaded);
        String previous = anchor;
        for (String name : chain) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, PATH_PREFIX + name);
            JsonElement value = out.get(id);
            if (value == null || !value.isJsonObject()) continue;
            out.put(id, withParent(value.getAsJsonObject(), modId + ":" + PATH_PREFIX + previous));
            previous = name;
        }
        return out;
    }

    /** A copy of {@code advancement} whose {@code parent} is {@code parent}. */
    static JsonObject withParent(JsonObject advancement, String parent) {
        JsonObject copy = advancement.deepCopy();
        copy.add("parent", new JsonPrimitive(parent));
        return copy;
    }
}
