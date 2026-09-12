package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Rewrites a requirement advancement's raw datapack JSON before vanilla parses it: applies the
 * relay override to the criterion's numeric field, and turns the description into a translatable
 * whose argument IS that number, so "Traverse %s carriages" always shows the value in force.
 *
 * <p>Pure functions over Gson trees, returning new objects — the input map that
 * {@code ServerAdvancementManager.apply} hands the mixin is never mutated. Only advancements under
 * {@code dungeontrain:dungeon_train/} are considered; everything else passes through untouched.</p>
 *
 * <p>The description is rewritten only when it is a bare {@code {"translate": key}} — a datapack
 * that already supplies {@code with}, or inlines a literal string, has made its own choice.</p>
 */
public final class RequirementJsonRewriter {

    /** The tab whose advancements can carry a requirement. Matches the relay's allowlist. */
    public static final String PATH_PREFIX = "dungeon_train/";

    private RequirementJsonRewriter() {}

    /**
     * The result of rewriting one advancement: the JSON to parse, and what was found in it.
     *
     * @param json        the (possibly new) advancement object
     * @param requirement the requirement it carries, empty for an advancement without one
     */
    public record Rewritten(JsonObject json, Optional<AdvancementRequirements.Requirement> requirement) {}

    /**
     * Rewrite the whole datapack map, returning a new map in the same iteration order, and hand
     * every requirement found to {@link AdvancementRequirements}.
     *
     * @param loaded    what {@code ServerAdvancementManager.apply} received
     * @param overrides effective relay overrides, advancement id → value
     * @param modId     the namespace whose advancements are ours
     */
    public static Map<ResourceLocation, JsonElement> rewriteAll(Map<ResourceLocation, JsonElement> loaded,
                                                                Map<ResourceLocation, Long> overrides,
                                                                String modId) {
        Map<ResourceLocation, JsonElement> out = new LinkedHashMap<>(loaded.size());
        Map<ResourceLocation, AdvancementRequirements.Requirement> found = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : loaded.entrySet()) {
            ResourceLocation id = e.getKey();
            JsonElement value = e.getValue();
            if (!isOurs(id, modId) || value == null || !value.isJsonObject()) {
                out.put(id, value);
                continue;
            }
            Rewritten r = rewrite(value.getAsJsonObject(), overrides.get(id));
            out.put(id, r.json());
            r.requirement().ifPresent(req -> found.put(id, req));
        }
        AdvancementRequirements.replace(found);
        return out;
    }

    /** True for {@code <modId>:dungeon_train/<name>}. */
    public static boolean isOurs(ResourceLocation id, String modId) {
        return id != null && modId.equals(id.getNamespace()) && id.getPath().startsWith(PATH_PREFIX);
    }

    /**
     * Rewrite one advancement. {@code override} is the relay's value for it, or null for none.
     *
     * <p>An override for an advancement whose JSON has no requirement field is ignored: the value
     * would have nothing to attach to, and inventing a field could change which trigger the
     * criterion parses as.</p>
     */
    public static Rewritten rewrite(JsonObject advancement, Long override) {
        Optional<Located> located = locate(advancement);
        if (located.isEmpty()) {
            return new Rewritten(advancement, Optional.empty());
        }
        Located at = located.get();
        long effective = override != null && override > 0 ? override : at.shipped();
        JsonObject out = deepCopy(advancement);
        JsonObject conditions = out.getAsJsonObject("criteria")
            .getAsJsonObject(at.criterion()).getAsJsonObject("conditions");
        conditions.add(at.field().jsonKey(), new JsonPrimitive(effective));
        rewriteDescription(out, at.field().descriptionArgument(effective));
        return new Rewritten(out, Optional.of(
            new AdvancementRequirements.Requirement(at.criterion(), at.field(), at.shipped(), effective)));
    }

    /** Where the requirement sits in an advancement object. */
    record Located(String criterion, RequirementField field, long shipped) {}

    /**
     * Find the requirement in {@code advancement}: the first criterion (in JSON order) whose
     * {@code conditions} carries a {@link RequirementField}. Every shipped requirement advancement
     * has exactly one.
     */
    static Optional<Located> locate(JsonObject advancement) {
        JsonElement criteriaEl = advancement.get("criteria");
        if (criteriaEl == null || !criteriaEl.isJsonObject()) return Optional.empty();
        for (Map.Entry<String, JsonElement> c : criteriaEl.getAsJsonObject().entrySet()) {
            if (!c.getValue().isJsonObject()) continue;
            JsonElement conditions = c.getValue().getAsJsonObject().get("conditions");
            if (conditions == null || !conditions.isJsonObject()) continue;
            JsonObject cond = conditions.getAsJsonObject();
            Optional<RequirementField> field = RequirementField.in(cond);
            if (field.isPresent()) {
                long shipped = cond.get(field.get().jsonKey()).getAsLong();
                return Optional.of(new Located(c.getKey(), field.get(), shipped));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code display.description: {"translate": k}} → {@code {"translate": k, "with": [arg]}}.
     * Anything else (a literal, an existing {@code with}, no display) is left as it is.
     */
    private static void rewriteDescription(JsonObject advancement, JsonElement argument) {
        JsonElement displayEl = advancement.get("display");
        if (displayEl == null || !displayEl.isJsonObject()) return;
        JsonObject display = displayEl.getAsJsonObject();
        JsonElement descEl = display.get("description");
        if (descEl == null || !descEl.isJsonObject()) return;
        JsonObject desc = descEl.getAsJsonObject();
        if (!desc.has("translate") || desc.has("with")) return;
        JsonArray with = new JsonArray();
        with.add(argument);
        desc.add("with", with);
    }

    private static JsonObject deepCopy(JsonObject o) {
        return o.deepCopy();
    }
}
